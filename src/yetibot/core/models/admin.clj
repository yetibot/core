(ns yetibot.core.models.admin
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-config]]))

(s/def ::users (s/coll-of string? :kind vector?))

(s/def ::commands (s/coll-of string? :kind vector?))

(s/def ::config (s/keys :opt-un [::users ::commands]))

(defn config [] (get-config ::config [:admin]))

(defn admin-only-command?
  "See if cmd is in the list of admin commands as defined by the instance 'config'."
  [cmd]
  (boolean ((-> (config) :value :commands set) cmd)))

(defn user-is-admin?
  "See if user is in the list of admin users as defined by the instance 'config'."
  [user-map]
  (let [{:keys [id]} user-map]
    (boolean ((-> (config) :value :users set) id))))
