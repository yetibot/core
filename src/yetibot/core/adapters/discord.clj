(ns yetibot.core.adapters.discord
  (:require
   [clojure.spec.alpha :as s]
   [yetibot.core.adapters.adapter :as a]
   [discljord 
    [connections :as ]
    ]
   [yetibot.core.chat :refer [base-chat-source chat-source chat-data-structure
                              *target* *adapter*]]))

(def discord-ping-interval-ms
  "How often to send Discord a ping event to ensure the connection is active"
  5000)

(def close-status
  "The status code to send Discord when intentionally closing the websocket"
  3337)

(s/def ::type #{"discord"})
(s/def ::token string?)
(s/def ::config (s/keys :req-un [::type
                                 ::token]
                        :opt-un []))

(def intents #{:guilds :guild-messages})

(defn start
  "start the discord connection"
  [intents, config]
  (let [event-ch (a/chan 100)
        connections-ch (a/connect-bot! (config ::token event-ch :intents intents))])
  )

(defn- channels [a])

(defn- send-msg [a msg]
  )

(defn- stop [adapter]
  )

(defrecord Discord
           [config
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
  (Discord
   {:config config
    :conn (atom nil)
    :connected? (atom false)
    :connection-latency (atom nil)
    :connection-last-active-timestamp (atom nil)
    :yetibot-user (atom nil)
    :should-ping? (atom false)}))
