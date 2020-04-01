(ns yetibot.core.db
  (:require
    [cuerdas.core :refer [snake]]
    [yetibot.core.db.util :refer [config qualified-table-name]]
    [clojure.pprint :refer [pprint]]
    [clojure.java.jdbc :as sql]
    [clojure.string :as string]
    [clojure.spec.alpha :as s]
    [yetibot.core.loader :refer [find-namespaces]]
    [taoensso.timbre :refer [trace info error]]))

(def db-ns-pattern #"(yetibot|plugins).*\.db\..+")

(s/def :schema/table string?)

(s/def :schema/spec (s/coll-of (s/or :keyword keyword?
                                     :string string?)
                               :kind vector? :min-count 1))

(s/def :schema/specs (s/coll-of :schema/spec :kind vector?))

(s/def ::schema (s/keys :req [:schema/table :schema/specs]))

(defn schemas []
  (let [nss (set (find-namespaces db-ns-pattern))]
    (if (empty? nss)
      (do
        (error "No database namespaces found")
        (throw (ex-info "No database namespaces found" {:nss nss})))
      (do
        (apply require nss)
        (for [n nss :when (and (ns-resolve n 'schema)
                               (s/valid? ::schema @(ns-resolve n 'schema)))]
          (deref (ns-resolve n 'schema)))))))

(defn table-exists?
  [table-name]
  (let [[{:keys [bool]}] (sql/with-db-connection [db-conn (:url (config))]
                           (sql/query
                             db-conn
                             ["select true from pg_class where relname= ?"
                              table-name]))]
    bool))

(defn spec-to-string
  "Extracted from clojure.java.jdbc/create-table-ddl"
  [entities spec]
  (try
    (string/join " " (cons (sql/as-sql-name entities (first spec))
                           (map name (rest spec))))
    (catch Exception _
      (throw (IllegalArgumentException.
               "column spec is not a sequence of keywords / strings")))))

(defn idempotent-add-columns!
  "Attempt to idempotently add each column individually to an existing table,
   failing gradcefully (log and do nothing) if that column already exists."
  [table-name table-specs {:keys [entities] :as opts}]
  (info "Alter" table-name "adding each column individually" table-specs)
  (run!
    (fn [column-spec]
      (let [column (spec-to-string entities column-spec)]
        (trace "Attempting to add" column)
        (try
          (do
            (sql/db-do-prepared
              (:url (config))
              (str "ALTER TABLE " table-name " ADD COLUMN " column))
            (info "✅ Added" column "to" table-name))
          (catch Throwable e
            ;; column failed to add, probably because it already existed, but
            ;; could have failed for another reason
            (trace column "already exists on" table-name
                  "or else something bad happened:" (.getMessage e))))))
    table-specs))

(defn idempotent-create-table!
  "Qualify the table-name with a prefix and idempotently create it"
  [table-name table-specs]
  (let [qualified-table (qualified-table-name table-name)
        exists? (table-exists? qualified-table)
        opts {:entities snake :conditional? true}]
    (info "Idempotently create or alter table" qualified-table)
    (if exists?
      ;; attempt to add each column individually to the existing table
      (do
        (info qualified-table "already exists. Altering...")
        (idempotent-add-columns! qualified-table table-specs opts))
      ;; create the table fresh
      (do
        (info qualified-table "does not exist. Creating...")
        (sql/db-do-commands
          (:url (config))
          (sql/create-table-ddl
            qualified-table table-specs
            opts))))))

(defonce connected? (atom false))

(defn start []
  (info "☐ Loading db schemas against" (:url (config)))
  (let [schemas-to-migrate (filter identity (schemas))]
    (info "Schemas" (with-out-str (pprint schemas-to-migrate)))
    (run!
      (fn [{:keys [schema/table schema/specs]}]
        (idempotent-create-table! table specs))
      schemas-to-migrate)
    (info "☑ Database loaded")
    (reset! connected? true)))
