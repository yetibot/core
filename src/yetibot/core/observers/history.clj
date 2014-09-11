(ns yetibot.core.observers.history
  (:require
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [obs-hook]]))

(obs-hook #{:message}
          (fn [event-info]
            ;; todo: rename channel to room
            (let [{:keys [adapter channel]} (:chat-source event-info)]
              (h/add {:chat-source-adapter adapter
                      :chat-source-room channel
                      :user-id (-> event-info :user :id str)
                      :body (:body event-info)}))))
