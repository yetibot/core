(ns yetibot.core.util.config
  (:require
    [clojure.java.io :refer [make-parents]]
    [taoensso.timbre :refer [color-str trace info warn error]]
    [clojure.java.io :refer [as-file]]
    [clojure.edn :as edn]
    [schema.core :as s]))

;; for human consumption
(defonce schema-by-path (atom {}))

(def default-config {:yetibot {}})

(defn config-exists? [path] (.exists (as-file path)))

(defn load-edn!
  "Attempts to load edn from `config-path`. If no file exists, a new file will
   be written with the value of `default-config`."
  [path]
  (try
    (if (config-exists? path)
      (edn/read-string (slurp path))
      (do
        (warn "Config does not exist at" (color-str :blue path) " - writing default config:" default-config)
        (make-parents path)
        (spit path default-config)
        default-config))
    (catch Exception e
      (error "Failed loading config: " e)
      nil)))

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
  (let [path (if (coll? path) path [path])]
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
