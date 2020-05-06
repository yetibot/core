(ns yetibot.core.adapters.web
  "The web adapter is the default, built-in adapter that powers the GraphQL API.
   It only implements a subset of the Adapter interace - the bare minimum to
   produce a chat source and respond to commands.

   There can only be on Web adapter instance. It is started automatically by
   adapters."
  (:require
   [clojure.spec.alpha :as s]
   [yetibot.core.adapters.adapter :as a]
   [yetibot.core.webapp.handler :as web]
   [yetibot.core.chat :refer [chat-source]]))

(s/def ::name string?)
(s/def ::config (s/keys :req-un [::name]))

(defn send-msg
  "This doesn't do anything"
  [a msg]
  msg)

(defn start [{:keys [connected?]}]
  (reset! connected? true)
  (web/start-web-server))

(defn stop [{:keys [connected?]}]
  (reset! connected? false)
  (web/stop-web-server))

(defrecord Web
           [config
            connected?]

  a/Adapter

  (a/uuid [_] (:name config))

  (a/platform-name [_] "Web")

  (a/channels [a] [])

  (a/send-paste [a msg] (send-msg a msg))

  (a/send-msg [a msg] (send-msg a msg))

  (a/join [_ channel]
    (str "Web adapter can't join channels ✌️"))

  (a/leave [_ channel]
    (str "Web adapter can't leave channels 👊"))

  (a/chat-source [_ channel] (chat-source channel))

  (a/stop [adapter] (stop adapter))

  (a/connected? [{:keys [connected?]}] @connected?)

  (a/connection-last-active-timestamp [_] -1)

  (a/connection-latency [_] -1)

  (a/start [adapter]
    (start adapter)))

(defn make-web
  [config]
  (map->Web
    {:config config
     :connected? (atom nil)}))
