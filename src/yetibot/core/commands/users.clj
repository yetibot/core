(ns yetibot.core.commands.users
  (:require [yetibot.core.models.users :as users]
            [clojure.string :as s]
            [yetibot.core.hooks :refer [cmd-hook]]))

(def chat-source "campfire/523232")

(defn show-users
  "users # list all users presently in the room"
  [{:keys [chat-source]}]
  (map (comp :username second) (users/get-users chat-source)))

; (defn reset
;   "users reset # reset the user list for the current room"
;   [_]
;   (users/reset-users)
;   "Users list reset complete")

(cmd-hook #"users"
          ; #"reset" reset
          #"^$" show-users)
