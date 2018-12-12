(ns yetibot.core.commands.users
  (:require
    [taoensso.timbre :as log]
    [yetibot.core.models.users :as users]
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn show-users
  "users # list all users presently in the channel"
  {:yb/cat #{:util}}
  [{:keys [chat-source]}]
  (log/info "show users for" chat-source)
  (let [users (users/get-users chat-source)]
    {:result/value (map :mention-name users)
     :result/data (map #(select-keys % users/min-user-keys) users)}))

(cmd-hook #"users"
          ; #"reset" reset
          #"^$" show-users)
