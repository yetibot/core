(ns yetibot.core.observers.history
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [obs-hook]]))

(defn history-observer
  [event-info]
  ;; todo: rename channel to room
  (info "history obs" event-info)
  (let [{:keys [adapter channel]} (:chat-source event-info)]
    (h/add {:chat-source-adapter adapter
            :chat-source-room channel
            :user-id (-> event-info :user :id str)
            :body (:body event-info)})))

(defonce observer (obs-hook #{:message} #'history-observer))
