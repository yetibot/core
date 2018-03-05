(ns yetibot.core.observers.history
  (:require
    [taoensso.timbre :refer [trace info warn error]]
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [obs-hook]]))

(defn history-observer
  [event-info]
  (trace "history obs" event-info)
  (let [{:keys [adapter room uuid]} (:chat-source event-info)]
    (h/add {:chat-source-adapter uuid
            :chat-source-room room
            :user-id (-> event-info :user :id str)
            :user-name (-> event-info :user :mention-name str)
            :body (:body event-info)})))

(defonce observer (obs-hook #{:message} #'history-observer))
