(ns yetibot.core.models.admin
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-config]]))

(s/def ::users (s/coll-of string? :kind vector?))

(s/def ::commands (s/coll-of string? :kind vector?))

(s/def ::config (s/keys :opt-un [::users ::commands]))

(defn config [] (get-config ::config [:admin]))

(comment
  ;; helps if you `source config/sample.env` to get
  ;;   real values
  (config)
  )

(defn admin-only-command? [cmd]
  (boolean ((-> (config) :value :commands set) cmd)))

(comment
  (admin-only-command? "obs")
  )

(defn user-is-admin? [{:keys [id]}]
  (boolean ((-> (config) :value :users set) id)))

(comment
  (user-is-admin? {:id "U123123"})
  )
