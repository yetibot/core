(ns yetibot.core.adapters.discord
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async :refer [chan close!]]
            [discljord.messaging :refer [get-guild-channels! start-connection! stop-connection! get-current-user! create-message! delete-message!]]
            [discljord.connections :as discord-ws]
            [yetibot.core.models.users :as users]
            [discljord.events :refer [message-pump!]]
            [taoensso.timbre :refer [info debug]]
            [yetibot.core.adapters.adapter :as a]
            [yetibot.core.handler :refer [handle-raw]]
            [yetibot.core.chat :refer [base-chat-source chat-source *target* *adapter*]]))

(s/def ::type #{"discord"})
(s/def ::token string?)
(s/def ::config (s/keys :req-un [::type ::token]))

(defmulti handle-event
  (fn [event-type event-data _conn yetibot-user]
    event-type))

(defmethod handle-event :default
  [event-type event-data _conn yetibot-user]
  (debug "🎉 UNHANDLED EVENT 🎉")
  (debug "Event type:" event-type)
  (debug "Event data:" (pr-str event-data))
  (debug "Author: " (pr-str (:author event-data)))
  (debug "Channel ID: " (pr-str (:channel-id event-data))))

;; also has :message-reaction-remove
(defmethod handle-event :message-reaction-add
  [event-type event-data _conn yetibot-user]
  (debug "🎉 NEW REACTION 🎉")
  (debug "Event type:" event-type)
  (debug "Event data:" (pr-str event-data))
  (debug "Channel ID: " (pr-str (:channel-id event-data)))
  (debug "Emoji: " (pr-str (-> event-data :emoji :name)))
  (debug "Thread Author ID: " (:message-author-id event-data))
  (debug "Yetibot user: " (:id @yetibot-user))
  (if (and
       (= (-> event-data :emoji :name) "❌")
       (= (:message-author-id event-data) (:id @yetibot-user)))
    (let [message-id (:message-id event-data)]
      (debug "Trying to delete message with id:" message-id)
      (if (delete-message! (:rest @_conn) (:channel-id event-data) message-id)
        (debug "Successfully deleted message")
        (debug "Failed to delete message)")))
    (let [emoji-name (-> event-data :emoji :name)]
      (if (not= (:message-author-id event-data) (:id @yetibot-user))
        (debug "You can only delete messages from Yetibot")
        (debug "No handler for emoji: " emoji-name))))

  (debug "Message Author ID:" (pr-str (-> event-data
                                          :message-author-id)))
  (debug "Username:" (pr-str (-> event-data
                                 :member
                                 :user
                                 :username)))
  (let [user-model (users/create-user
                    (-> event-data
                        :message-author-id)
                    (-> event-data
                        :member
                        :user
                        :username))
        cs (assoc (chat-source (:channel-id event-data))
                  :raw-event event-data)
        reaction (-> event-data :emoji :name)] 
    (handle-raw cs user-model :react yetibot-user
                {:reaction reaction
                           ;; body of the message reacted to
                 :body "Not working yet"
                           ;; user of the message that was reacted to
                 :message-user (:message-author-id event-data)})))


(defmethod handle-event :message-create
  [event-type event-data _conn yetibot-user]
  (debug "🎉 NEW MESSAGE 🎉")
  (debug "Event type:" event-type)
  (debug "Event data:" (pr-str event-data))
  (debug "Author: " (pr-str (:author event-data)))
  (debug "Author ID: " (-> event-data
                           :author
                           :id))
  (debug "Author Username: " (-> event-data
                                 :author
                                 :username))
  (debug "Channel ID: " (pr-str (:channel-id event-data)))
  (debug "Yetibot user: " (:id @yetibot-user))
  (do
    (debug "Handling Message")
    (if (not= (:id @yetibot-user) (-> event-data
                                      :author
                                      :id))
      (let [user-model (users/create-user
                        (-> event-data
                            :author
                            :username)
                        (event-data :author :id))
            message (:content event-data)
            cs (assoc (chat-source (:channel-id event-data))
                      :raw-event event-data)]
        (debug "chat source: " (pr-str cs))
        (debug "running handle-raw")

        (binding [*target* (:channel-id event-data)]
          (handle-raw
           (chat-source (:channel-id event-data))
           user-model
           :message
           @yetibot-user
           {:body message})))
      (debug "Message from Yetibot => ignoring"))))



(defn start
  "start the discord connection"
  [adapter _conn config _connected? bot-id yetibot-user]
  (debug "starting discord connection")

  (binding [*adapter* adapter]
    (let [event-channel (chan 100)
          message-channel (discord-ws/connect-bot! (:token config) event-channel :intents #{:guilds :guild-messages :guild-message-reactions :direct-messages :direct-message-reactions})
          rest-connection (start-connection! (:token config))]
      (let [retcon {:event  event-channel
                    :message message-channel
                    :rest    rest-connection}]
        (reset! _conn retcon)

        (debug (pr-str _conn))

        (reset! _connected? true)
        (reset! bot-id {:id @(get-current-user! rest-connection)})
        (reset! yetibot-user @(get-current-user! rest-connection))
        (message-pump! event-channel (fn [event-type event-data] (handle-event event-type event-data _conn yetibot-user)))))))



(defn- channels [a]
  (let [guild-channels (get-guild-channels!)]
    (debug "Guild Channels: " (pr-str guild-channels))
    (guild-channels)))

(defn- send-msg [adapter msg conn]
  (debug "Trying to send message: " msg)
  (debug "Target is: " *target*)
  (debug "Adapter: " (pr-str adapter))
  (debug "conn: " (pr-str conn))
  (debug "rest: " (pr-str (:rest @conn)))
  (create-message! (:rest @conn) *target* :content msg))

(defn stop
  "stop the discord connection"
  [adapter _conn]
  (debug "Closing Discord" (a/uuid adapter))
  (stop-connection! (:message @_conn))
  (close!           (:event @_conn)))

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

  (a/send-paste [a msg] (send-msg a msg conn))

  (a/send-msg [a msg] (send-msg a msg conn))

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
