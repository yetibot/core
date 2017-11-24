(ns yetibot.core.db.observe
  (:require
    [cuerdas.core :refer [kebab snake]]
    [clojure.java.jdbc :as sql]
    [yetibot.core.db.util :refer [config qualified-table-name]]))

(def schema
  {:schema/table "observer"
   :schema/specs [[:id :serial "PRIMARY KEY"]
                  [:user-id :text "NOT NULL"]
                  [:pattern :text]
                  [:user-pattern :text]
                  [:channel-pattern :text]
                  [:event-type :text]
                  [:cmd :text "NOT NULL"]]})

(defn create-obs
  [entity]
  (sql/with-db-connection [db-conn (:url (config))]
    (sql/insert!
      db-conn
      (qualified-table-name (:schema/table schema))
      entity
      {:entities snake})))

(defn delete-obs
  [id]
  (sql/with-db-connection [db-conn (:url (config))]
    (sql/delete!
      db-conn
      (qualified-table-name (:schema/table schema))
      ["id = ?" id])))

(defn find-all-obs
  []
  (sql/with-db-connection [db-conn (:url (config))]
    (sql/query
      db-conn
      [(str "SELECT * FROM "
            (qualified-table-name (:schema/table schema)))]
      {:identifiers kebab})))
