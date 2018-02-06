(ns yetibot.core.commands.users
  (:require
    [taoensso.timbre :as log]
    [yetibot.core.models.users :as users]
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn show-users
  "users # list all users presently in the room"
  {:yb/cat #{:util}}
  [{:keys [chat-source]}]
  (log/info "show users for" chat-source)
  (map :mention-name (users/get-users chat-source)))

(cmd-hook #"users"
          ; #"reset" reset
          #"^$" show-users)
