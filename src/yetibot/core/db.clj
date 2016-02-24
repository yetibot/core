(ns yetibot.core.db
  (:require
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.loader :refer [find-namespaces]]
    [datomico.db :as db]
    [datomico.core :as dc]
    [datomic.api :as api]
    [taoensso.timbre :refer [info warn error]]))

(def db-ns-pattern #"(yetibot|plugins).*\.db\..+")

(defn schemas []
  (let [nss (set (find-namespaces db-ns-pattern))]
    (apply require nss)
    (for [n nss :when (ns-resolve n 'schema)]
      (deref (ns-resolve n 'schema)))))

(def default-url "datomic:mem://yetibot")

(defn config []
  {:url (or (:value (get-config String [:yetibot :db :datomic :url]))
            default-url)})

(defn start [& [opts]]
  (info "☐ Loading Datomic schemas at" (:url (config)))
  (dc/start (merge opts {:uri (:url (config))
                         :schemas (filter identity (schemas))}))
  (info "☑ Datomic connected"))

(def repl-start (partial start {:dynamic-vars true}))
