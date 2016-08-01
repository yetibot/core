(ns yetibot.core.observers.users
  (:require
    [taoensso.timbre :as log]
    [yetibot.core.models.users :as users]
    [yetibot.core.hooks :refer [obs-hook]]
    [yetibot.core.chat :refer [chat-data-structure]]))

(obs-hook
  #{:enter}
  (fn [event-info]
    (log/debug "enter" event-info)
    (users/add-user (:chat-source event-info)
                    (:user event-info))))

(obs-hook
  #{:leave}
  (fn [event-info]
    (log/debug "leave" event-info)
    (users/remove-user (:chat-source event-info)
                       (-> event-info :user :id))))
