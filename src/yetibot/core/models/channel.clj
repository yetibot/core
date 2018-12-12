(ns yetibot.core.models.channel
  (:require
    [schema.core :as sch]
    [yetibot.core.adapters.adapter :refer [active-adapters uuid]]
    [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.config-mutable :as config]
    [clojure.string :as s]))

(def config-path [:room])

(def cat-settings-key :disabled-categories)

(def channel-config-defaults
  "Provides both a list of all available settings as well as their defaults"
  {;; whether to send things like global messages and Tweets to a channel
   "broadcast" "false"
   ;; JIRA project
   "jira-project" ""
   ;; default Jenkins project
   "jenkins-default" ""})

(defn merge-on-defaults [channel-config]
  (merge channel-config-defaults channel-config))

(defn settings-by-uuid
  "Returns the full settings map for an adapter given the adapter's uuid."
  [uuid]
  (:value (config/get-config sch/Any (conj config-path uuid))))

(defn settings-for-channel [uuid channel]
  (info "settings for channel" uuid channel)
  (merge-on-defaults (get (settings-by-uuid uuid) channel {})))

(defn settings-for-chat-source
  "Convenience fn that takes a chat-source, extracts the correct keys and uses
   them to call settings-for-channel"
  [{:keys [uuid room]}]
  (settings-for-channel uuid room))

(defn apply-settings
  "Takes a fn to apply to current value of a setting for a given channel"
  [uuid channel f]
  (config/apply-config! (conj config-path uuid channel) f)
  (config/reload-config!))

(defn update-settings
  "Updates or creates new setting k = v for a given channel"
  [uuid channel k v]
  (apply-settings
    uuid channel
    (fn [current-val-if-exists]
      (assoc current-val-if-exists k v))))
