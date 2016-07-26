(ns yetibot.core.util.config
  (:require
    [taoensso.timbre :refer [trace info warn error]]
    [schema.core :as s]))

;; for human consumption
(defonce schema-by-path (atom {}))

(defn get-config
  "Lookup configuration in a config tree.
   Returns one of:
   {:error :invalid
    :message \"$value does not validate against schema: $schema\"
    :schema $schema}
   {:error :not-found :message $path}
   {:value $valid-value}"
  [config schema path]
  ;; store up all schemas to build a complete schema-by-path representation as
  ;; a convenient reference
  (swap! schema-by-path assoc path schema)
  (trace "get-config" schema path)
  (let [path (if (coll? path) path [path]) ]
    (if-let [value (get-in config path)]
      (try
        (s/validate schema value)
        ;; valid and found
        {:value value}
        (catch Exception e
          ;; invalid against schema
          ;; (warn e)
          {:error :invalid
           :message (str value " does not validate against schema: " (pr-str schema))
           :schema schema}))
      ;; not found
      {:error :not-found :message path})))
