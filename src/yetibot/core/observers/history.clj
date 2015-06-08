(ns yetibot.core.observers.history
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [obs-hook]]))

(defn history-observer
  [event-info]
  (info "history obs" event-info)
  (let [{:keys [adapter room]} (:chat-source event-info)]
    (h/add {:chat-source-adapter adapter
            :chat-source-room room
            :user-id (-> event-info :user :id str)
            :user-name (-> event-info :user :name str)
            :body (:body event-info)})))

(defonce observer (obs-hook #{:message} #'history-observer))
