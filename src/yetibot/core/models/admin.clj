(ns yetibot.core.models.admin
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-config]]))

(s/def ::users (s/coll-of string? :kind vector?))

(s/def ::commands (s/coll-of string? :kind vector?))

(s/def ::config (s/keys :opt-un [::users ::commands]))

(defn config [] (get-config ::config [:admin]))

(defn admin-only-command? [cmd]
  (boolean ((-> (config) :value :commands set) cmd)))

(defn user-is-admin? [{:keys [id]}]
  (boolean ((-> (config) :value :users set) id)))
