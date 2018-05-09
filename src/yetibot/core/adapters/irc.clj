(ns yetibot.core.adapters.irc
  (:require
    [schema.core :as s]
    [clojure.set :refer [difference union intersection]]
    [yetibot.core.adapters.adapter :as a]
    [taoensso.timbre :as log :refer [info debug]]
    [rate-gate.core :refer [rate-limit]]
    [irclj
     [core :as irc]
     [connection :as irc-conn]]
    [yetibot.core.models.users :as users]
    [clojure.string :refer [split-lines join]]
    [yetibot.core.config :as config]
    [yetibot.core.config-mutable :as mconfig]
    [yetibot.core.chat :refer [base-chat-source chat-source
                               chat-data-structure send-msg-for-each
                               *target* *adapter*] :as chat]
    [yetibot.core.util.format :as fmt]
    [yetibot.core.handler :refer [handle-raw]]))

(declare join-or-part-with-current-channels connect start)

(defn rooms [{:keys [current-channels] :as a}] @current-channels)

(defn config-path [adapter]
 [:yetibot :irc (a/uuid adapter)])

(defn rooms-config-path [adapter]
  (conj (config-path adapter) :rooms))

(def wait-before-reconnect 30000)

(def irc-max-message-length 420)

(defn split-msg-into-irc-max-length-chunks [msg]
  (map join (partition-all irc-max-message-length msg)))

(def send-msg
  "Rate-limited function for sending messages to IRC. It's rate limited in order
   to prevent 'Excess Flood' kicks"
  (rate-limit
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
          (log/info "SocketException, trying to reconnect in" wait-before-reconnect "ms")
          (Thread/sleep wait-before-reconnect)
          (connect adapter)
          (start adapter))))
    3 900))

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

(def channels-schema #{s/Str})

(def mutable-config-schema {:rooms #{s/Str}})

(defn reload-and-reset-config!
  "Reloads config from disk, then uses adapter uuid to lookup the correct config
   map for this instance and resets the config atom with it."
  [{:keys [mutable-config] :as a}]
  (mconfig/reload-config!)
  (let [new-conf (:value (mconfig/get-config
                           mutable-config-schema (config-path a)))]
    (info "reloaded config, now:" new-conf)
    (reset! mutable-config new-conf)))

(defn set-rooms-config
  "Accepts a function that will be passed the current rooms config. Return value
   of function will be used to set the new rooms config"
  [adapter f]
  (log/info "rooms config path is" (rooms-config-path adapter)
            (f (rooms adapter)))
  (mconfig/update-config! (rooms-config-path adapter) (f (rooms adapter)))
  (reload-and-reset-config! adapter))

(defn add-room-to-config [a room]
  (log/info "add room" room "to irc config")
  (log/info
    (set-rooms-config a #(set (conj % room)))))

(defn remove-room-from-config [a room]
  (log/info "remove room from irc config")
  (set-rooms-config a (comp set (partial filter #(not= % room)))))

(defn join-room [a room]
  (add-room-to-config a room)
  (join-or-part-with-current-channels a)
  (str "Joined " room))

(defn leave-room [a room]
  (remove-room-from-config a room)
  (join-or-part-with-current-channels a)
  (str "Left " room))

(defn fetch-users [a]
  (doall (map #(irc-conn/write-irc-line @(:conn a) "WHO" %) (rooms a))))

(defn recognized-chan? [a chan] ((set (rooms a)) chan))

(defn construct-yetibot-from-nick [nick]
  {:username nick
   :id (str "~" nick) })

(defn handle-message
  "Recieve and handle messages from IRC. This can either be in channels yetibot
   is listening in, or it can be a private message. If yetibot does not
   recognize the :target, reply back to user with PRIVMSG."
   [a irc info]
   (let [config (:config a)
         {yetibot-nick :nick} @irc
         yetibot-user (construct-yetibot-from-nick yetibot-nick)
         ;; print `irc` to see what's available
         ;; also maybe this is a good time to spec it out :D
         user-id (:user info)
         chan (or (recognized-chan? a (:target info)) (:nick info))
         user (users/get-user (chat-source chan) user-id)]
     (log/info "handle message" info "from" chan yetibot-user)
     (binding [*target* chan]
       (handle-raw (chat-source chan) user :message (:text info) yetibot-user))))

(defn handle-part [a _ {:keys [target] :as info}]
  (binding [*target* target]
    (handle-raw (chat-source target)
                (create-user info) :leave nil nil)))

(defn handle-join [a _ {:keys [target] :as info}]
  (log/debug "handle-join" info)
  (binding [*target* target]
    (handle-raw (chat-source (:target info))
                (create-user info) :enter nil nil)))

(defn handle-nick [a _ info]
  (let [[nick] (:params info)
        id (:user info)]
    (users/update-user (chat-source (:target info)) id {:username nick :name nick})))

(defn handle-who-reply [a _ info]
  (log/debug "352" info)
  (let [{[_ channel user _ _ nick] :params} info]
    (log/info "add user" channel user nick)
    (users/add-user (chat-source channel)
                    (create-user {:user user :nick nick}))))

(defn handle-invite [a _ info]
  (log/info "handle invite" info)
  (join-room a (second (:params info))))

(defn handle-raw-log [adapter _ b c] (log/trace b c))

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
               :join #'handle-join
               :nick #'handle-nick
               :invite #'handle-invite
               :366 #'handle-end-of-names
               :352 #'handle-who-reply}]
          [event-name
           ;; these are the args passed from irclj event fire
           (fn [& event-args]
             ;; the hack. gross 😭
             (binding [*adapter* adapter]
               (apply (partial event-handler adapter) event-args)))])))

(defn connect [{:keys [config conn] :as a}]
  (let [username (or (:username config) (str "yetibot_" (rand-int 1000)))
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
   current-channels equal to configured rooms."
  [{:keys [conn mutable-config current-channels] :as adapter}]
  (let [configured-rooms (:rooms @mutable-config)
        to-part (difference @current-channels configured-rooms)
        to-join (difference configured-rooms @current-channels)]
    (info "configured-rooms" configured-rooms)
    (debug "channels" @current-channels)
    (debug "to-part" to-part)
    (debug "to-join" to-join)
    (reset! current-channels configured-rooms)
    (doall (map #(irc/join @conn %) to-join))
    (doall (map #(irc/part @conn %) to-part))
    (fetch-users adapter)))

(defn part
  "Not currently used"
  [{:keys [conn]} channel]
  (when conn (irc/part @conn channel)))

(defn start
  "Join and fetch all users with WHO <channel>"
  [{:keys [mutable-config config conn] :as adapter}]
  (binding [*adapter* adapter]
    (info "starting IRC with" config)
    (info "*adapter* is" (log/color-str :blue (pr-str *adapter*)))
    (reload-and-reset-config! adapter)
    (connect adapter)
    (join-or-part-with-current-channels adapter)))

(defn stop
  "Kill the irc conection"
  [{:keys [conn]}]
  (when @conn (irc/kill @conn))
  (reset! conn nil))

(defrecord IRC [config mutable-config current-channels conn]

  ; config
  ; Holds the immutable configuration for a single IRC Adapter instance.

  ; mutable-config
  ; Loaded from disk in start. It stores the IRC channels that Yetibot should
  ; join on startup. When Yetibot is commanded to join or leave channels,
  ; mutable-config is updated and persisted to disk.

  ; current-channels
  ; Atom holding the set of current channels that Yetibot is listening on. This
  ; is necessary to track in addition to mutable config in order to diff
  ; channels when modifying config to know which ones to part or join.

  ; conn
  ; An atom that holds the IRC connection

  a/Adapter

  (a/uuid [_] (:name config))

  (a/platform-name [_] "IRC")

  (a/rooms [a] (rooms a))

  (a/send-paste [a msg] (send-paste a msg))

  (a/send-msg [a msg] (send-msg a msg))

  (a/join [a room] (join-room a room))

  (a/leave [a room] (leave-room a room))

  (a/chat-source [_ room] (chat-source room))

  (a/stop [adapter] (stop adapter))

  (a/start [adapter] (start adapter)))

(defn make-irc
  [config]
  (->IRC config (atom {}) (atom #{}) (atom nil)))
