(ns yetibot.core.adapters.discord
  (:require [clojure.spec.alpha :as spec]
            [clojure.core.async :as async]
            [discljord.messaging :as messaging]
            [discljord.connections :as discord-ws]
            [yetibot.core.models.users :as users]
            [discljord.events :as events]
            [taoensso.timbre :as timbre]
            [yetibot.core.adapters.adapter :as adapter]
            [yetibot.core.handler :as handler]
            [yetibot.core.chat :as chat]))

(spec/def ::type #{"discord"})
(spec/def ::token string?)
(spec/def ::config (spec/keys :req-un [::type ::token]))

(defmulti handle-event
  (fn [event-type event-data _conn yetibot-user]
    event-type))

;; also has :message-reaction-remove
(defmethod handle-event :message-reaction-add
  [event-type event-data _conn yetibot-user]
  (let [message-id (:message-id event-data)
        channel-id (:channel-id event-data)
        message-author-id (:message-author-id event-data)
        emoji-name (-> event-data
                       :emoji
                       :name)
        rest-conn (:rest @_conn)
        yetibot? (= message-author-id (:id @yetibot-user))]
    (if (and
         (= emoji-name "❌")
         (= yetibot? true))
      (messaging/delete-message! rest-conn channel-id message-id)
      (if (= yetibot? true)
        (timbre/debug "We don't handle" emoji-name "from yetibot")
        (let [cs (assoc (chat/chat-source channel-id)
                        :raw-event event-data)
              user-model (assoc (users/get-user cs message-author-id)
                                :yetibot? yetibot?)
              message-content (:content @(messaging/get-channel-message! rest-conn channel-id message-id))]
          (handler/handle-raw
           cs
           user-model
           :react
           @yetibot-user
           {:reaction emoji-name
            :body message-content
            :message-user message-author-id}))))))


(defmethod handle-event :message-create
  [event-type event-data _conn yetibot-user]
  (if (not= (:id @yetibot-user) (-> event-data
                                    :author
                                    :id))
    (let [user-model (users/create-user
                      (-> event-data
                          :author
                          :username)
                      (event-data :author :id))
          message (:content event-data)
          cs (assoc (chat/chat-source (:channel-id event-data))
                    :raw-event event-data)]
      (binding [chat/*target* (:channel-id event-data)]
        (handler/handle-raw
         cs
         user-model
         :message
         @yetibot-user
         {:body message})))
    (timbre/debug "Message from Yetibot => ignoring")))

(defn start
  "start the discord connection"
  [{conn :conn
    config :config
    connected? :connected?
    bot-id :bot-id
    yetibot-user :yetibot-user :as adapter}]
  (timbre/debug "starting discord connection")

  (binding [chat/*adapter* adapter]
    (let [event-channel (async/chan 100)
          message-channel (discord-ws/connect-bot! (:token config) event-channel :intents #{:guilds :guild-messages :guild-message-reactions :direct-messages :direct-message-reactions})
          rest-connection (messaging/start-connection! (:token config))
          retcon {:event  event-channel
                  :message message-channel
                  :rest    rest-connection}]
      (reset! conn retcon)

      (timbre/debug (pr-str conn))

      (reset! connected? true)
      (reset! bot-id {:id @(messaging/get-current-user! rest-connection)})
      (reset! yetibot-user @(messaging/get-current-user! rest-connection))
      (events/message-pump! event-channel (fn [event-type event-data] (handle-event event-type event-data conn yetibot-user))))))

(defn- channels [a]
  (let [guild-channels (messaging/get-guild-channels!)]
    (timbre/debug "Guild Channels: " (pr-str guild-channels))
    (guild-channels)))

(defn- send-msg [{:keys [conn]} msg]
  (messaging/create-message! (:rest @conn) chat/*target* :content msg))

(defn stop
  "stop the discord connection"
  [{:keys [conn] :as adapter}]
  (timbre/debug "Closing Discord" (adapter/uuid adapter))
  (messaging/stop-connection! (:message @conn))
  (async/close!           (:event @conn)))

(defrecord Discord
           [config
            bot-id
            conn
            connected?
            connection-last-active-timestamp
            connection-latency
            should-ping?
            yetibot-user]

  adapter/Adapter

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

  (a/chat-source [_ channel] (chat/chat-source channel))

  (a/stop [adapter] (stop adapter))

  (a/connected? [{:keys [connected?]}]
    @connected?)

  (a/connection-last-active-timestamp [_]
    @connection-last-active-timestamp)

  (a/connection-latency [_]
    @connection-latency)

  (a/start [adapter]
    (start adapter)))

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
