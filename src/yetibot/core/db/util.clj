(ns yetibot.core.db.util
  (:require
    [clojure.string :refer [join]]
    [cuerdas.core :refer [kebab snake]]
    [clojure.java.jdbc :as sql]
    [yetibot.core.config :refer [get-config]]))

(def config-shape {:url String :table {:prefix String}})

(def default-db-url "postgresql://localhost:5432/yetibot")

(def default-table-prefix "yetibot_")

(defn config []
  (or (:value (get-config config-shape [:db]))
      {:url default-db-url
       :table {:prefix default-table-prefix}}))

(defn qualified-table-name
  [table-name]
  (str (-> (config) :table :prefix) table-name))

(defn create
  [table entity]
  (sql/with-db-connection [db-conn (:url (config))]
    (sql/insert!
      db-conn
      (qualified-table-name table)
      entity
      {:entities snake})))

(defn delete
  [table id]
  (sql/with-db-connection [db-conn (:url (config))]
    (sql/delete!
      db-conn
      (qualified-table-name table)
      ["id = ?" id])))

(defn find-all
  [table]
  (sql/with-db-connection [db-conn (:url (config))]
    (sql/query
      db-conn
      [(str "SELECT * FROM "
            (qualified-table-name table))]
      {:identifiers kebab})))

(defn transform-where-map
  "Return a vector of where-keys and where-args to use in a select or update"
  [where-map]
  (let [where-keys (join " " (map (fn [[k _]] (str (snake k) "=?")) where-map))
        where-args (vals where-map)]
    [where-keys where-args]))

(defn find-where
  [table where-map]
  (let [[where-keys where-args] (transform-where-map where-map)]
    (seq
      (sql/with-db-connection [db-conn (:url (config))]
        (sql/query
          db-conn
          (into
            [(str "SELECT * FROM " (qualified-table-name table)
                  " WHERE " where-keys)]
            where-args)
          {:identifiers kebab})))))

(defn update-where
  [table where-map attrs]
  (let [[where-keys where-args] (transform-where-map where-map)]
    (sql/with-db-connection [db-conn (:url (config))]
      (sql/update!
        db-conn
        (qualified-table-name table)
        ;; transform attr keys to snake case
        (into {} (for [[k v] attrs] [(snake k) v]))
        (apply vector where-keys where-args)))))
