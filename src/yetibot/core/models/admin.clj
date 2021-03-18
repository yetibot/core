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

(defn admin-only-command?
  "See if cmd is in the list of admin commands as defined
   by the instance 'config'. Arity/2 allows for passing in
   custom config to compare against, mainly used for testing."
  ([cmd] (admin-only-command? cmd (config)))
  ([cmd cfg-map] (boolean
                  ((-> cfg-map :value :commands set) cmd))))

(comment
  (admin-only-command? "obs")
  (let [cfg {:value {:commands ["obs"]}}]
    (println (admin-only-command? "obs" cfg))
    (println (admin-only-command? "fail" cfg)))
  )

(defn user-is-admin?
  "See if user is in the list of admin users as defined
   by the instance 'config'. Arity/2 allows for passing in
   custom config to compare against, mainly used for testing."
  ([user-map] (user-is-admin? user-map (config)))
  ([user-map cfg-map]
   (let [{:keys [id]} user-map]
     (boolean
      ((-> cfg-map :value :users set) id)))))

(comment
  (user-is-admin? {:id "U123123"})
  (user-is-admin? {:id "fail"})
  )
