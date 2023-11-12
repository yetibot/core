(ns yetibot.core.adapters.discord
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async :refer [chan close!]]
            [discljord.messaging :refer [get-guild-channels! start-connection! stop-connection! get-current-user! create-message!]]
            [discljord.connections :as discord-ws]
            [yetibot.core.models.users :as users]
            [discljord.events :refer [message-pump!]]
            [taoensso.timbre :refer [info]]
            [yetibot.core.adapters.adapter :as a]
            [yetibot.core.handler :refer [handle-raw]]
            [yetibot.core.chat :refer [base-chat-source chat-source *target* *adapter*]]))

(s/def ::type #{"discord"})
(s/def ::token string?)
(s/def ::config (s/keys :req-un [::type ::token]))

;;  WIP - Need to figure out chat source
(defn find-yetibot-user
  [_conn cs]
  (let [yetibot-uid (:id @(get-current-user! (:rest _conn)))]
    (users/get-user cs yetibot-uid)))

(defmulti handle-event
  (fn [event-type event-data _conn yetibot-user]
    event-type))

(defmethod handle-event :message-create
  [event-type event-data _conn yetibot-user]
  (info "🎉 NEW EVENT! 🎉")
  (info "Event type:" event-type)
  (info "Event data:" (pr-str event-data))
  (info "Author: " (pr-str (:author event-data)))
  (info "Channel ID: " (pr-str (:channel-id event-data)))
  (if (= (:content event-data) "!disconnect")
    (discord-ws/disconnect-bot! (:connection _conn))
    (do
      (info "Handling Message")
      (let [user-model (users/create-user
                        (event-data :author :username)
                        (event-data :author :id))
            message (:content event-data)]
        (info "chat source: " (pr-str (chat-source (:channel-id event-data))))
        (info "running handle-raw")
        (binding [*target* (:channel-id event-data)]
          (handle-raw
           (chat-source (:channel-id event-data))
           user-model
           :message
           @yetibot-user
           {:body message}))))))



(defn start
  "start the discord connection"
  [adapter _conn config _connected? bot-id yetibot-user]
  (info "starting discord connection")

  (binding [*adapter* adapter]
    (let [event-channel (chan 100)
          message-channel (discord-ws/connect-bot! (:token config) event-channel :intents #{:guilds :guild-messages})
          rest-connection (start-connection! (:token config))]
      (let [retcon {:event  event-channel
                    :message message-channel
                    :rest    rest-connection}]
        (reset! _conn retcon)

        (info (pr-str _conn))
        (reset! bot-id {:id @(get-current-user! rest-connection)})
        (reset! yetibot-user @(get-current-user! rest-connection))
        (message-pump! event-channel (fn [event-type event-data] (handle-event event-type event-data _conn yetibot-user)))))))



(defn- channels [a]
  (let [guild-channels (get-guild-channels!)]
    (info "Guild Channels: " (pr-str guild-channels))
    (guild-channels)))

(defn- send-msg [adapter msg]
  (info "Trying to send message: " msg)
  (info "Target is: " *target*)
  (info "Adapter: " (pr-str adapter))
  (info "rest connection: " (pr-str (adapter :conn :rest)))
  (create-message! (adapter :conn :rest) *target* :content msg))

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
    (start adapter conn config connected? bot-id yetibot-user)))

(defn make-discord
  [config]
  (map->Discord
   {:config config
    :bot-id (atom nil)
    :conn (atom nil)
    :connected? (atom false)
    :connection-latency (atom nil)
    :connection-last-active-timestamp (atom nil)
    :yetibot-user (atom nil)
    :should-ping? (atom false)}))
