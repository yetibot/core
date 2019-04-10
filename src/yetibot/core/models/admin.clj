(ns yetibot.core.models.admin
  (:require
    [schema.core :as s]
    [yetibot.core.config :refer [get-config]]))

(defn config [] (get-config {:users [s/Str] :commands [s/Str]} [:admin]))

(defn admin-only-command? [cmd]
  (boolean ((-> (config) :value :commands set) cmd)))

(defn user-is-admin? [{:keys [id]}]
  (boolean ((-> (config) :value :users set) id)))
