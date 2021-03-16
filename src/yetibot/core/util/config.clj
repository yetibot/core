(ns yetibot.core.util.config
  (:require
    [clojure.java.io :refer [as-file make-parents]]
    [taoensso.timbre :refer [color-str trace debug info warn error]]
    [clojure.edn :as edn]
    [clojure.spec.alpha :as s]
    [expound.alpha :as expound]))

;; for human consumption
(defonce spec-by-path (atom {}))

(def default-config {:yetibot {}})

(defn config-exists? [path] (.exists (as-file path)))

(comment
  (config-exists? "nope")
  (config-exists? "config/config.sample.edn")
  )

(defn load-edn!
  [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception e
      (debug "No config found at" (pr-str path))
      nil)))

(comment
  (load-edn! "nope.edn")
  (load-edn! "config/config.sample.edn")
  )

(defn load-or-create-edn!
  "Attempts to load edn from `config-path`. If no file exists, a new file will
   be written with the value of `default-config`."
  [path]
  (try
    (if (config-exists? path)
      (load-edn! path)
      (do
        (warn "Config does not exist at" (color-str :blue path) " - writing default config:" default-config)
        (make-parents path)
        (spit path default-config)
        default-config))
    (catch Exception e
      (error "Failed loading config: " e)
      nil)))

(comment
  (load-or-create-edn! "nope.edn")
  (load-edn! "config/config.sample.edn")
  )

(defn get-config
  "Lookup configuration in a config tree.
   Returns one of:
   {:error :invalid
    :message \"$value does not validate against spec: $spec\"
    :spec $spec}
   {:error :not-found :message $path}
   {:value $valid-value}"
  [config spec path]
  ;; store up all specs to build a complete spec-by-path representation as
  ;; a convenient reference
  (swap! spec-by-path assoc path spec)
  (trace "get-config" spec path)
  (let [path (if (coll? path) path [path])]
    (if-let [value (get-in config path)]
      (if (s/valid? spec value)
        ;; valid and found
        {:value value}
        ;; invalid against spec
        {:error :invalid
         :message (expound/expound-str spec value)
         :spec spec})
      ;; not found
      {:error :not-found :message path})))
