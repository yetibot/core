(ns yetibot.core.db
  (:require
    [yetibot.core.config :refer [get-config config-for-ns conf-valid?]]
    [yetibot.core.loader :refer [find-namespaces]]
    [datomico.db :as db]
    [datomico.core :as dc]
    [datomic.api :as api]
    [taoensso.timbre :refer [info warn error]]))

(def db-ns-pattern #"(yetibot|plugins).*\.db\..+")

(defn schemas []
  (let [nss (find-namespaces db-ns-pattern)]
    (apply require nss)
    (for [n nss :when (ns-resolve n 'schema)]
      (deref (ns-resolve n 'schema)))))

(defn start [& [opts]]
  (if (conf-valid? (get-config :yetibot :db))
    (do
      (info "☐ Loading Datomic schemas at" (:datomic-url (get-config :yetibot :db)))
      (dc/start (merge opts {:uri (:datomic-url (get-config :yetibot :db))
                             :schemas (filter identity (schemas))}))
      (info "☑ Datomic connected"))
    (warn ":datomic-url is not configured, unable to connect.")))

(def repl-start (partial start {:dynamic-vars true}))
