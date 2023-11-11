(ns yetibot.core.adapters.discord
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async :refer [chan close!]]
            [discljord.messaging :refer [start-connection! stop-connection! get-current-user! create-message!]]
            [discljord.connections :as discord-ws]
            [yetibot.core.models.users :as users]
            [discljord.events :refer [message-pump!]]
            [taoensso.timbre :refer [info]]
            [yetibot.core.adapters.adapter :as a]
            [yetibot.core.chat :refer [chat-source]]))

(s/def ::type #{"discord"})
(s/def ::token string?)
(s/def ::config (s/keys :req-un [::type ::token]))

;;  WIP - Need to figure out chat source
(defn find-yetibot-user
  [_conn cs]
  (let [yetibot-uid (:id @(get-current-user! (:rest _conn)))]
    (users/get-user cs yetibot-uid)))

;; handle-event expects these 2 arguments
;; How can I wrap it to pass in the conn as well in Clojure?
(defmulti handle-event
  (fn [event-type event-data]
    event-type))

(defmethod handle-event :message-create
  [event-type event-data conn]
  (println event-data)
  (if (= (:content event-data) "!disconnect")
    (discord-ws/disconnect-bot! (:connection conn))
    (when-not (:bot (:author event-data))
      (create-message! (:rest conn) (:channel-id event-data) :content "Helloooo"))))

(defn start
  "start the discord connection"
  [adapter _conn config _connected? bot-id]
  (info "starting discord connection")
  (let [event-channel (chan 100)
        message-channel (discord-ws/connect-bot! (:token config) event-channel :intents #{:guilds :guild-messages})
        rest-connection (start-connection! (:token config))]
    (reset! _conn {:event  event-channel
                   :message message-channel
                   :rest    rest-connection})
    (reset! bot-id {:id @(get-current-user! (:rest _conn))})

    ;; This is where I would want to wrap `handle-event` and pass in a conn
    (message-pump! (:event _conn) handle-event)))

(defn- channels [a])

(defn- send-msg [a msg])

(defn stop 
  "stop the discord connection"
  [adapter _conn]  
  (info "Closing Discord" (a/uuid adapter))
  (stop-connection! (:message _conn))
  (close!           (:event _conn)))

(defrecord Discord
           [config
            bot-id
            conn
            connected?
            connection-last-active-timestamp
            connection-latency
            should-ping?
            yetibot-user]

  a/Adapter

  (a/uuid [_] (:name config))

  (a/platform-name [_] "Discord")

  (a/channels [a] (channels a))

  (a/send-paste [a msg] (send-msg a msg))

  (a/send-msg [a msg] (send-msg a msg))

  (a/join [_ channel]
    (str
     "Discord bots such as myself can't join channels on their own. Use "
     "/invite from the channel you'd like me to join instead.✌️"))

  (a/leave [_ channel]
    (str
     "Discord bots such as myself can't leave channels on their own. Use "
     "/kick from the channel you'd like me to leave instead. 👊"))

  (a/chat-source [_ channel] (chat-source channel))

  (a/stop [adapter] (stop adapter conn))

  (a/connected? [{:keys [connected?]}]
    @connected?)

  (a/connection-last-active-timestamp [_]
    @connection-last-active-timestamp)

  (a/connection-latency [_]
    @connection-latency)

  (a/start [adapter]
    (start adapter conn config connected? bot-id)))

(defn make-discord
  [config]
  (Discord
   {:config config
    :bot-id (atom nil)
    :conn (atom nil)
    :connected? (atom false)
    :connection-latency (atom nil)
    :connection-last-active-timestamp (atom nil)
    :yetibot-user (atom nil)
    :should-ping? (atom false)}))
