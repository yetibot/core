(ns yetibot.core.config
  "Config is made available via [environ](https://github.com/weavejester/environ).
   See yetibot.core.config-mutable for configuration that can be changed at 
   runtime, such as which channels to join on IRC."
  (:require
    [yetibot.core.util.config :as uc]
    [dec :refer [explode]]
    [environ.core :refer [env]]
    [taoensso.timbre :refer [info warn error]]
    [clojure.pprint :refer [pprint]]
    [clojure.string :refer [blank? split]]))

(def config-prefix "yetibot")

(defn config-from-env-or-file
  "If a `CONFIG_PATH` env var is specified, load config from it.
   Otherwise, load config from env and explode it into nested maps."
  []
  (if-let [path (env :config-path)]
    (uc/load-edn! path)
    (explode
      (into {} (filter (fn [[k v]] (.startsWith (name k) "yetibot")) env)))))

(defonce ^:private config (atom (config-from-env-or-file)))

(def get-config (partial uc/get-config @config))

(defn conf-valid?
  "Ensure all values are not blank"
  [c]
  (and c (every? (complement (comp blank? str)) (vals c))))
