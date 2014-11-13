(ns yetibot.core.adapters.irc
  (:require
    [clojure.set :refer [difference union intersection]]
    [taoensso.timbre :as log]
    [rate-gate.core :refer [rate-limit]]
    [yetibot.core.chat]
    [irclj
     [core :as irc]
     [connection :as irc-conn]]
    [yetibot.core.models.users :as users]
    [clojure.string :refer [split-lines join]]
    [yetibot.core.config :refer [update-config get-config config-for-ns
                                 reload-config conf-valid?]]
    [yetibot.core.chat :refer [chat-data-structure send-msg-for-each register-chat-adapter] :as chat]
    [yetibot.core.util.format :as fmt]
    [yetibot.core.handler :refer [handle-raw]]))

(defonce conn (atom nil))

(declare join-or-part-with-current-channels connect start)

(defn config [] (get-config :yetibot :adapters :irc))

(defn rooms [] (:rooms (config)))

(def rooms-config-path [:yetibot :adapters :irc :rooms])

(defn channels [] (set (keys (rooms))))

(defn channels-with-broadcast-enabled []
  (->> (rooms)
       (filter #(:broadcast? (second %)))
       (map first)))

(defn chat-source [channel] {:adapter :irc :room channel})

(def wait-before-reconnect 30000)

(def irc-max-message-length 420)

(defn split-msg-into-irc-max-length-chunks [msg]
  (map join (partition-all irc-max-message-length msg)))

(def ^{:dynamic true
       :doc "the channel or user that a message came from"} *target*)

(def send-msg
  "Rate-limited function for sending messages to IRC. It's rate limited in order
   to prevent 'Excess Flood' kicks"
  (rate-limit
    (fn [msg]
      (log/info "send message to channel" *target*)
      (try
        (if (> (count msg) irc-max-message-length)
          (doall (map send-msg (split-msg-into-irc-max-length-chunks msg)))
          (irc/message @conn *target* msg))
        (catch java.net.SocketException e
          ; it must have disconnect, try reconnecting again
          (log/info "SocketException, trying to reconnect in" wait-before-reconnect "ms")
          (Thread/sleep wait-before-reconnect)
          (connect)
          (start))))
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
  [p] (send-msg-for-each (prepare-paste p)))

(defn set-rooms-config
  "Accepts a function that will be passed the current rooms config. Return value
   of function will be used to set the new rooms config"
  [f]
  (apply update-config (conj rooms-config-path (f (rooms))))
  (reload-config))

(defn add-room-to-config [room]
  (log/info "add room to irc config")
  (set-rooms-config (fn [rooms] (conj rooms [room {:broadcast? false}]))))

(defn remove-room-from-config [room]
  (log/info "remove room from irc config")
  (set-rooms-config #(dissoc % room)))

(defn set-room-broadcast [room broadcast?]
  (let [opts-updater (fn [room-opts] (assoc room-opts :broadcast? broadcast?))]
    (set-rooms-config #(update-in % [room] opts-updater))))

(defn join-room [room]
  (add-room-to-config room)
  (join-or-part-with-current-channels))

(defn leave-room [room]
  (remove-room-from-config room)
  (join-or-part-with-current-channels))

(def messaging-fns
  {:msg send-msg
   :paste send-paste
   :join join-room
   :leave leave-room
   :set-room-broadcast set-room-broadcast
   :rooms rooms})

(defn send-to-all
  "Send message to all targets that have :broadcast? true."
  [msg]
  (doall (map #(binding [*target* %
                         chat/*messaging-fns* messaging-fns]
                 (chat-data-structure msg))
              (channels-with-broadcast-enabled))))


(defn fetch-users []
  (doall (map #(irc-conn/write-irc-line @conn "WHO" %) (channels))))

(defn recognized-chan? [c] ((set (channels)) c))

; example info args for handle-message
; - priv msg
; {:text !echo hi, :target yetibotz, :command PRIVMSG, :params [yetibotz !echo hi],
;  :raw :devth!~devth@1.1.1.1 PRIVMSG yetibotz :!echo hi, :host 2.2.2.2,
;  :user ~devth, :nick devth}
; - message from #yeti channel
; {:text !echo ook, :target #yeti, :command PRIVMSG, :params [#yeti !echo ook],
;  :raw :devth!~devth@1.1.1.1 PRIVMSG #yeti :!echo ook, :host 2.2.2.2,
;  :user ~devth, :nick devth}

(defn handle-message
  "Recieve and handle messages from IRC. This can either be in channels yetibot
   is listening in, or it can be a private message. If yetibot does not
   recognize the :target, reply back to user with PRIVMSG."
  [_ info]
  (log/info "handle message" info)
  (let [user-id (:user info)
        chan (or (recognized-chan? (:target info)) (:nick info))
        user (users/get-user (chat-source chan) user-id)]
    (binding [*target* chan
              yetibot.core.chat/*messaging-fns* messaging-fns]
      (handle-raw (chat-source chan) user :message (:text info)))))

(defn handle-part [_ info]
  (handle-raw (chat-source (:target info))
              (create-user info) :leave nil))

(defn handle-join [_ info]
  (log/debug "handle-join" info)
  (handle-raw (chat-source (:target info))
              (create-user info) :enter nil))

(defn handle-nick [_ info]
  (let [[nick] (:params info)
        id (:user info)]
    (users/update-user (chat-source (:target info)) id {:username nick :name nick})))

(defn handle-who-reply [_ info]
  (log/debug "352" info)
  (let [{[_ channel user _ _ nick] :params} info]
    (log/info "add user" channel user nick)
    (users/add-user (chat-source channel)
                    (create-user {:user user :nick nick}))))

(defn handle-invite [_ info]
  (log/info "handle invite" info)
  (join-room (second (:params info))))

(defn raw-log [a b c] (log/debug b c))

(defn handle-end-of-names
  "Callback for end of names list from IRC. Currently not doing anything with it."
  [irc event]
  (let [users (-> @irc :channels vals first :users)]))

(def callbacks {:privmsg #'handle-message
                :raw-log #'raw-log
                :part #'handle-part
                :join #'handle-join
                :nick #'handle-nick
                :invite #'handle-invite ; verify
                :366 #'handle-end-of-names
                :352 #'handle-who-reply})

(defn connect []
  (reset!
    conn
    (irc/connect
      (:host (config)) (read-string (or (:port (config)) "6667")) (:username (config))
      :ssl? (:ssl? (config))
      :callbacks callbacks)))

(defonce current-channels (atom []))
(defn join-or-part-with-current-channels []
  (let [chs (channels)
        to-part (difference @current-channels chs)
        to-join (difference chs @current-channels)]
    (reset! current-channels (channels))
    (doall (map #(irc/join @conn %) to-join))
    (doall (map #(irc/part @conn %) to-part))
    (fetch-users)))

(defn start
  "Join and fetch all users with WHO <channel>"
  []
  (if (conf-valid? (config))
    (do
      (register-chat-adapter 'yetibot.core.adapters.irc)
      (connect)
      (join-or-part-with-current-channels))
    (log/info "✗ IRC is not configured")))

(defn stop
  "Kill the irc conection"
  []
  (irc/kill @conn)
  (reset! conn nil))

(defn part [channel]
  (when conn (irc/part @conn channel)))
