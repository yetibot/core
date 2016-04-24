(ns yetibot.core.models.room
  (:require
    [yetibot.core.adapters.adapter :refer [active-adapters uuid]]
    [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.config-mutable :as config]
    [clojure.string :as s]))

(def config-path [:yetibot :room])

(def cat-settings-key :disabled-categories)

(def room-config-defaults
  "Provides both a list of all available settings as well as their defaults"
  {;; whether to send things like global messages and Tweets to a room
   "broadcast" "false"
   ;; JIRA project
   "jira-project" ""
   ;; default Jenkins project
   "jenkins-default" ""})

(defn merge-on-defaults [room-config]
  (merge room-config-defaults room-config))

(defn settings-by-uuid
  "Returns the full settings map for an adapter given the adapter's uuid."
  [uuid]
  (config/get-config {} (conj config-path uuid)))

(defn settings-for-room [uuid room]
  (merge-on-defaults (get (settings-by-uuid uuid) room {})))

(defn settings-for-chat-source
  "Convenience fn that takes a chat-source, extracts the correct keys and uses
   them to call settings-for-room"
  [{:keys [uuid room]}]
  (settings-for-room uuid room))

(defn apply-settings
  "Takes a fn to apply to current value of a setting for a given room"
  [uuid room f]
  (config/apply-config! (conj config-path uuid room) f)
  (config/reload-config!))

(defn update-settings
  "Updates or creates new setting k = v for a given room"
  [uuid room k v]
  (apply-settings
    uuid room
    (fn [current-val-if-exists]
      (assoc current-val-if-exists k v))))
