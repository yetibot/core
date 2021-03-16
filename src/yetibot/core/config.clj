(ns yetibot.core.config
  "Config is made available via
   [environ](https://github.com/weavejester/environ)"
  (:require [dec :refer [explode]]
            [clojure.string :refer [blank?]]
            [environ.core :refer [env]]
            [taoensso.timbre :refer [info]]
            [yetibot.core.util.config :as uc]))

(def config-prefixes
  (if-not (blank? (env :yetibot-env-prefix))
    ;; Allow providing the env prefix key from env overriding the default `:yb`
    ;; or `:yetibot` prefixes. This allows the user to specify their own env
    ;; namespace. This was necessary when using the Yetibot Helm Chart, because
    ;; Kubernetes sets a bunch of env vars using the metadata from the pod as a
    ;; prefix (i.e. )
    [(keyword (env :yetibot-env-prefix))]
    ;; default env prefixes. For example, these pick up env vars like:
    ;; - YB_FOO="bar"
    ;; - YETIBOT_QUX="baz"
    ;; and combine them into a single map without prefixes
    [:yb :yetibot]))

(comment
  config-prefixes
  )

(def config-from-env-disabled? (env :yetibot-env-config-disabled))

(def config-path (or (env :config-path) "config/config.edn"))

(defn merge-possible-prefixes
  "Given a config map merge any possible allowed yb prefixes"
  [m]
  (->> (select-keys m config-prefixes)
       vals
       (reduce merge)))

(comment
  (merge-possible-prefixes {:yetibot {:a 1}})
  )

(defn prefixed-env-vars
  "Return a map of all env vars with known prefixes. If no env-vars are
   passed, uses actual env vars obtained from environ.core/env."
  ([] (prefixed-env-vars env))
  ([env-vars]
   (into {} (filter (fn [[k _]]
                      (some
                       (fn [prefix] (.startsWith (name k) (name prefix)))
                       config-prefixes))
                    env-vars))))

(comment
  (env :yetibot-db-url)
  (prefixed-env-vars)
  env
  )

(defn config-from-env-or-file
  "If args not passed in, load file-cfgs from `config-path` and
   env-cfgs from valid YB prefixed env variables, if not `config-from-env-disabled?`,
   then merge. Otherwise, use passed in args."
  ([] (config-from-env-or-file (uc/load-edn! config-path) (prefixed-env-vars)))
  ([file-cfgs] (config-from-env-or-file file-cfgs (prefixed-env-vars)))
  ([file-cfgs env-cfgs]
   (merge
    (merge-possible-prefixes file-cfgs)
    (if-not config-from-env-disabled?
      (merge-possible-prefixes (explode env-cfgs))
      (info "Configuration from environment is disabled")))))

(comment
  (config-from-env-or-file {:yetibot {:a 1}} {:yetibot-b-c 2})
  (config-from-env-or-file {:yetibot {:a 1}})
  )

(defonce ^:private config (atom (config-from-env-or-file)))

(def get-config (partial uc/get-config @config))
