(ns yetibot.core.db
  (:require
    [cuerdas.core :refer [snake]]
    [yetibot.core.db.util :refer [config qualified-table-name]]
    [clojure.pprint :refer [pprint]]
    [clojure.java.jdbc :as sql]
    [yetibot.core.loader :refer [find-namespaces]]
    [datomico.db :as db]
    [datomico.core :as dc]
    [datomic.api :as api]
    [schema.core :as sch]
    [taoensso.timbre :refer [info warn error]]))

(def db-ns-pattern #"(yetibot|plugins).*\.db\..+")

;; TODO use clojure.spec
(defn valid-schema-map? [schema]
  (and (:schema/table schema) (:schema/specs schema)))

(defn schemas []
  (let [nss (set (find-namespaces db-ns-pattern))]
    (apply require nss)
    (for [n nss :when (and (ns-resolve n 'schema)
                           (valid-schema-map? @(ns-resolve n 'schema)))]
      (deref (ns-resolve n 'schema)))))

(defn idempotent-create-table!
  "Qualify the table-name with a prefix and idempotently create it"
  [table-name table-specs]
  (let [qualified-table (qualified-table-name table-name)]
    (info "Idempotently create table" qualified-table)
    (sql/db-do-commands
      (:url (config))
      (sql/create-table-ddl qualified-table table-specs
                            {:entities snake :conditional? true}))))

(defn start []
  (info "☐ Loading db schemas against" (:url (config)))
  (let [schemas-to-migrate (filter identity (schemas))]
    (info "Schemas" (with-out-str (pprint schemas-to-migrate)))
    (run!
      (fn [{:keys [schema/table schema/specs]}]
        (idempotent-create-table! table specs))
      schemas-to-migrate)
    (info "☑ Database loaded")))
