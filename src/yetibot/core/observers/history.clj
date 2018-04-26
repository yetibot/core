(ns yetibot.core.observers.history
  (:require
    [taoensso.timbre :refer [trace debug]]
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [obs-hook]]))

(defn history-observer
  [{:keys [chat-source user] :as event-info}]
  (debug "history obs" event-info)
  (let [{:keys [adapter room uuid]} chat-source]
    (h/add {:chat-source-adapter uuid
            :chat-source-room room
            :user-id (-> event-info :user :id str)
            :user-name (-> event-info :user :mention-name str)
            :is-yetibot (boolean (:yetibot? user))
            :body (:body event-info)})))

(defonce observer (obs-hook #{:message} #'history-observer))
