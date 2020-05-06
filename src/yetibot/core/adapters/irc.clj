(ns yetibot.core.adapters.irc
  (:require
    [clojure.set :refer [difference union intersection]]
    [clojure.spec.alpha :as s]
    [yetibot.core.adapters.adapter :as a]
    [taoensso.timbre :as log :refer [info debug]]
    [throttler.core :refer [throttle-fn]]
    [irclj
     [core :as irc]
     [connection :as irc-conn]]
    [yetibot.core.models.users :as users]
    [yetibot.core.models.channel :as channel]
    [clojure.string :refer [split-lines join includes?]]
    [yetibot.core.chat :refer [base-chat-source chat-source
                               chat-data-structure send-msg-for-each
                               *target* *adapter*] :as chat]
    [yetibot.core.handler :refer [handle-raw]]))

(s/def ::type string?)

(s/def ::host string?)

(s/def ::port string?)

(s/def ::ssl string?)

(s/def ::username string?)

(s/def ::password string?)

(s/def ::config (s/keys :req-un [::type]
                        :opt-un [::username
                                 ::ssl
                                 ::host
                                 ::port
                                 ::password]))

(declare join-or-part-with-current-channels connect start stop)

(defn channels [{:keys [current-channels] :as a}] @current-channels)

(def wait-before-reconnect
  "We need to delay before attempting to reconnect or else IRC will think the
   username is still taken since it waits awhile to show the user as offline."
  30000)

(def irc-max-message-length 420)

(defn split-msg-into-irc-max-length-chunks [msg]
  (map join (partition-all irc-max-message-length msg)))

(defn reconnect [adapter reason]
  (log/info reason ", trying to reconnect in" wait-before-reconnect "ms")
  (stop adapter)
  (Thread/sleep wait-before-reconnect)
  (start adapter))

(def send-msg
  "Rate-limited function for sending messages to IRC. It's rate limited in order
   to prevent 'Excess Flood' kicks"
  (throttle-fn
    (fn [{:keys [conn] :as adapter} msg]
      (log/info "send message to channel" *target*)
      (try
        (if (> (count msg) irc-max-message-length)
          (doall (map
                   (partial send-msg adapter)
                   (split-msg-into-irc-max-length-chunks msg)))
          (irc/message @conn *target* msg))
        (catch java.net.SocketException e
          ; it must have disconnect, try reconnecting again
          ; TODO add better retry, like Slack
          (reconnect adapter "SocketException"))))
    1 :second 5))

(defn- create-user [info]
  (let [username (:nick info)
        id (:user info)]
    (users/create-user username (merge info {:id id}))))

(def prepare-paste
  "Since pastes are sent as individual messages, blank lines would get
   translated into \"No Results\" by the chat namespace. Instead of a blank
   line, map it into a single space."
  (comp (fn [coll] (map #(if (empty? %) " " %) coll))
        split-lines))

(defn send-paste
  "In IRC there are new newlines. Each line must be sent as a separate message, so
   split it and send one for each"
  [a p] (send-msg-for-each (prepare-paste p)))

(defn reload-and-reset-config!
  "Reloads config from disk, then uses adapter uuid to lookup the correct config
   map for this instance and resets the config atom with it."
  [{:keys [channel-config] :as a}]
  (let [uuid (a/uuid a)
        new-conf (channel/get-yetibot-channels uuid)]
    (info "reloaded config, now:" new-conf)
    (reset! channel-config new-conf)))

(defn set-channels-config
  "Accepts a function that will be passed the current channels config. Return
   value of function will be used to set the new channels config"
  [adapter f]
  (let [uuid (a/uuid adapter)]
    (channel/set-yetibot-channels uuid (f (channels adapter)))
    (reload-and-reset-config! adapter)))

(defn add-channel-to-config [a channel]
  (log/info "add channel" channel "to irc config")
  (log/info
    (set-channels-config a #(set (conj % channel)))))

(defn remove-channel-from-config [a channel]
  (log/info "remove channel from irc config")
  (set-channels-config a (comp set (partial filter #(not= % channel)))))

(defn join-channel [a channel]
  (add-channel-to-config a channel)
  (join-or-part-with-current-channels a)
  (str "Joined " channel))

(defn leave-channel [a channel]
  (remove-channel-from-config a channel)
  (join-or-part-with-current-channels a)
  (str "Left " channel))

(defn fetch-users [a]
  (doall (map #(irc-conn/write-irc-line @(:conn a) "WHO" %) (channels a))))

(defn recognized-chan? [a chan] ((set (channels a)) chan))

(defn construct-yetibot-from-nick [nick]
  {:username nick
   :id (str "~" nick) })

(defn handle-message
  "Recieve and handle messages from IRC. This can either be in channels yetibot
   is listening in, or it can be a private message. If yetibot does not
   recognize the :target, reply back to user with PRIVMSG."
   [a irc info]
   (log/info "handle-message" (pr-str info))
   (let [config (:config a)
         {yetibot-nick :nick} @irc
         yetibot-user (construct-yetibot-from-nick yetibot-nick)
         user-id (:user info)
         chan (or (recognized-chan? a (:target info)) (:nick info))
         user (users/get-user (chat-source chan) user-id)]
     (log/info "handle message" info "from" chan yetibot-user)
     (binding [*target* chan]
       (handle-raw (chat-source chan)
                   user :message yetibot-user {:body (:text info)}))))

(defn handle-part
  "Event that fires when someone leaves a channel that Yetibot is listening in"
  [a irc {:keys [params] :as info}]
  (log/debug "handle-part" (pr-str info))
  (let [target (first params)]
    (binding [*target* target]
      (handle-raw (chat-source (first params))
                  (create-user info) :leave
                  (construct-yetibot-from-nick (:nick @irc))
                  {}))))

(defn handle-join [a irc {:keys [params] :as info}]
  (log/debug "handle-join" info)
  (let [target (first params)]
    (binding [*target* target]
      (handle-raw (chat-source target)
                  (create-user info) :enter
                  (construct-yetibot-from-nick (:nick @irc))
                  {}))))

(defn handle-nick [a _ info]
  (let [[nick] (:params info)
        id (:user info)]
    (users/update-user
      (chat-source (first (:params info))) id {:username nick :name nick})))

(defn handle-who-reply [a _ info]
  (log/debug "352" info)
  (let [{[_ channel user _ _ nick] :params} info]
    (log/info "add user" channel user nick)
    (users/add-user (chat-source channel)
                    (create-user {:user user :nick nick}))))

(defn next-nick [nick]
  "Select next alternative nick"

  ;; rfc1459:
  ;; Each client is distinguished from other clients by a unique
  ;; nickname having a maximum length of nine (9) characters.
  (let [nick-len (count nick)]
    (if (< nick-len 9)
      (str nick "_")

      (loop [nick (vec nick) i (dec nick-len)]
        (when (>= i 0)
          (let [c (Character/digit (nth nick i) 10)
                overflow? (= c 9)
                next-nick (assoc nick i (mod (inc c) 10))]
            (if overflow?
              (recur next-nick (dec i))
              (clojure.string/join next-nick))))))))

(defn handle-nick-in-use [a _ info]
  (log/debug "433" info)
  (let [{[_ nick] :params} info
        {:keys [nick-state]} a
        {:keys [retries nick]} @nick-state
        can-retry? (< retries 10)
        next-nick (when can-retry? (next-nick nick))
        next-state (when next-nick {:retries (inc retries) :nick next-nick})]

    (reset! nick-state next-state)
    (if next-state
      (reconnect a (str "nick " nick " is already in use"))
      (log/info "Nick retries exhausted."))))

(defn handle-invite [a _ info]
  (log/info "handle invite" info)
  (join-channel a (second (:params info))))

(defn handle-notice [a _ info]
  (log/debug "handle notice" info)
  (let [info-msg (second (:params info))]
    (when (includes? info-msg "throttled due to flooding")
      (log/warn "NOTICE: " info-msg))))

(defn handle-raw-log [adapter _ b c]
  (log/trace b c))

(defn handle-kick [a _ {:keys [params] :as info}]
  (log/info "kicked" (pr-str info))
  (leave-channel a (first params)))

(defn handle-end-of-names
  "Callback for end of names list from IRC. Currently not doing anything with it."
  [adapter irc event]
  (let [users (-> @irc :channels vals first :users)]))

(defn callbacks
  "Build a map of event handlers, with the adapter partially applied to each
   handler.

   Note: even though the handlers get the adapter as their first arg, some of
   the functions they call still rely on *adapter* to be correctly bound. Fix
   this! https://github.com/devth/yetibot.core/issues/25

   As a hack, we can re-bind here."
  [adapter]
  (into {}
        (for [[event-name event-handler]
              {:privmsg #'handle-message
               :raw-log #'handle-raw-log
               :part #'handle-part
               :kick #'handle-kick
               :join #'handle-join
               :notice #'handle-notice
               :nick #'handle-nick
               :invite #'handle-invite
               :366 #'handle-end-of-names
               :352 #'handle-who-reply
               :433 #'handle-nick-in-use}]
          [event-name
           ;; these are the args passed from irclj event fire
           (fn [& event-args]
             ;; the hack. gross 😭
             (binding [*adapter* adapter]
               (apply (partial event-handler adapter) event-args)))])))



(defn connect [{:keys [config conn nick-state] :as a}]
  (when-not @nick-state
    (reset!
     nick-state
     {:retries 0
      :nick (or (:username config) (str "yetibot_" (rand-int 1000)))}))

  (let [username (:nick @nick-state)
        host (or (:host config) "irc.freenode.net")
        port (read-string (or (:port config) "6667"))
        ssl? (boolean (:ssl config))]
    (info "Connecting to IRC"
          {:host host :port port :ssl? ssl? :username username})
    (reset!
      conn
      (irc/connect host port username
                   :ssl? ssl?
                   :callbacks (callbacks a)))))

(defn join-or-part-with-current-channels
  "Determine the diff between current-channels and configured channels to
   determine which to join and part. After resolving the diff, set
   current-channels equal to configured channels."
  [{:keys [conn channel-config current-channels] :as adapter}]
  (let [configured-channels @channel-config
        to-part (difference @current-channels configured-channels)
        to-join (difference configured-channels @current-channels)]
    (info "configured-channels" configured-channels)
    (debug "channels" @current-channels)
    (debug "to-part" to-part)
    (debug "to-join" to-join)
    (reset! current-channels configured-channels)
    (doall (map #(irc/join @conn %) to-join))
    (doall (map #(irc/part @conn %) to-part))
    (fetch-users adapter)))

(defn part
  "Not currently used"
  [{:keys [conn]} channel]
  (when conn (irc/part @conn channel)))

(defn start
  "Join and fetch all users with WHO <channel>"
  [{:keys [channel-config config conn nick-state] :as adapter}]
  (binding [*adapter* adapter]
    (info "starting IRC with" config)
    (info "*adapter* is" (log/color-str :blue (pr-str *adapter*)))
    (reload-and-reset-config! adapter)
    (connect adapter)
    (join-or-part-with-current-channels adapter)))

(defn stop
  "Kill the irc conection"
  [{:keys [current-channels conn]}]
  (when-let [c @conn] (irc/kill c))
  (reset! current-channels #{})
  (reset! conn nil))

(defrecord IRC
  [config channel-config current-channels conn nick-state]

  ; config
  ; Holds the immutable configuration for a single IRC Adapter instance.

  ; channel-config
  ; Loaded from database. It stores the IRC channels that Yetibot should
  ; join on startup. When Yetibot is commanded to join or leave channels,
  ; channel-config is updated and persisted to the database.

  ; current-channels
  ; Atom holding the set of current channels that Yetibot is listening on. This
  ; is necessary to track in addition to channel-config in order to diff
  ; channels when modifying config to know which ones to part or join.

  ; conn
  ; An atom that holds the IRC connection

  a/Adapter

  (a/uuid [_] (:name config))

  (a/platform-name [_] "IRC")

  (a/channels [a] (channels a))

  (a/send-paste [a msg] (send-paste a msg))

  (a/send-msg [a msg] (send-msg a msg))

  (a/join [a channel] (join-channel a channel))

  (a/leave [a channel] (leave-channel a channel))

  (a/chat-source [_ channel] (chat-source channel))

  (a/stop [adapter] (stop adapter))

  (a/connected? [_] (when-let [c @conn] (-> c deref :ready? deref)))

  (a/connection-last-active-timestamp [_] 0) ;; TODO implement

  (a/connection-latency [_] 0) ;; TODO implement

  (a/start [adapter] (start adapter)))


(defn make-irc
  [config]
  (->IRC config (atom {}) (atom #{}) (atom nil) (atom nil)))
