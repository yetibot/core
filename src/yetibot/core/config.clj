(ns yetibot.core.config
  "Config is made available via [environ](https://github.com/weavejester/environ).
   See yetibot.core.config-mutable for configuration that can be changed at
   runtime, such as which channels to join on IRC."
  (:require [dec :refer [explode]]
            [environ.core :refer [env]]
            [taoensso.timbre :refer [info]]
            [yetibot.core.util.config :as uc]))

(def config-prefixes [:yb :yetibot])

(def config-from-env-disabled? (env :yetibot-env-config-disabled))

(def config-path (or (env :config-path) "config/config.edn"))

(defn merge-possible-prefixes
  "Given a config map merge any possible allowed yb prefixes"
  [m]
  (->> (select-keys m config-prefixes)
       vals
       (reduce merge)))


(defn prefixed-env-vars
  "Return a map of all env vars with known prefixes"
  []
  (into {} (filter (fn [[k v]]
                     (some
                       (fn [prefix] (.startsWith (name k) (name prefix)))
                       config-prefixes))
                   env)))

(defn config-from-env-or-file
  "Try loading config from `config-path`.
   Then load config from env as well, unless config-from-env-disabled? is
   truthy.
   Then merge."
  []
  (merge
    (merge-possible-prefixes (uc/load-edn! config-path))
    (if-not config-from-env-disabled?
      (let [env-vars (prefixed-env-vars)]
        (merge-possible-prefixes (explode env-vars)))
      (info "Configuration from environment is disabled"))))

(defonce ^:private config (atom (config-from-env-or-file)))

(def get-config (partial uc/get-config @config))
