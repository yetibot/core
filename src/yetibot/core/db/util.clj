(ns yetibot.core.db.util
  (:require
    [schema.core :as sch]
    [clojure.string :refer [blank? join]]
    [cuerdas.core :refer [kebab snake]]
    [clojure.java.jdbc :as sql]
    [taoensso.timbre :refer [debug info color-str]]
    [yetibot.core.config :refer [get-config]]))

(def config-shape {:url String
                   (sch/optional-key :table) {:prefix String}})

(def default-db-url "postgresql://localhost:5432/yetibot")

(def default-table-prefix "yetibot_")

(defn default-fields
  "All tables get these fields by default"
  []
  [[:id :serial "PRIMARY KEY"]
   [:created-at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"]])

(defn config []
  (merge
    ;; default
    {:url default-db-url
     :table {:prefix default-table-prefix}}
    (:value (get-config config-shape [:db]))))

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
  ([table] (find-all table {}))
  ([table {:keys [identifiers]}]
   (sql/with-db-connection [db-conn (:url (config))]
     (sql/query
       db-conn
       [(str "SELECT * FROM "
             (qualified-table-name table))]
       {:identifiers (or identifiers kebab)}))))

(defn transform-where-map
  "Return a vector of where-keys and where-args to use in a select or update"
  [where-map]
  (if (empty? where-map)
    ["" []]
    (let [where-keys (join " AND " (map (fn [[k _]] (str (snake k) "=?")) where-map))
          where-args (vals where-map)]
      [where-keys where-args])))

(defn empty-where?
  [where]
  (not (and where
            (not (blank? (first where)))
            )))
            ;; this check is too aggressive, omit
            ;; (not (empty? (second where))))))

(defn combine-wheres
  [where1 where2]
  (cond
    (empty-where? where2) where1
    (empty-where? where1) where2
    :else (let [[w1-query w1-args] where1
                [w2-query w2-args] where2]
            [(str w1-query " AND " w2-query)
             (into (vec w1-args)
                   (vec w2-args))])))

(defn query
  "Query with WHERE"
  [table {;; provide either where/map
          ;;   or where/clause and where/args
          ;;   or both (they will be combined)
          select-clause :select/clause
          where-map :where/map
          where-clause :where/clause
          where-args :where/args
          ;; optional
          group-clause :group/clause
          having-clause :having/clause
          order-clause :order/clause
          offset-clause :offset/clause
          limit-clause :limit/clause
          identifiers :query/identifiers}]
  (let [select-clause (or select-clause "*")
        [where-clause where-args] (combine-wheres
                                    (transform-where-map where-map)
                                    [where-clause where-args])

        _ (info "db query"
                (color-str :blue
                           {:where-clause where-clause
                            :where-args where-args}))

        sql-query (into
                    [(str "SELECT " select-clause
                          " FROM " (qualified-table-name table)
                          (when-not (blank? where-clause) (str " WHERE " where-clause))
                          (when group-clause (str " GROUP BY " group-clause))
                          (when having-clause (str " HAVING " having-clause))
                          (when order-clause (str " ORDER BY " order-clause))
                          (when offset-clause (str " OFFSET " offset-clause))
                          (when limit-clause (str " LIMIT " limit-clause)))]
                    where-args)
        ]
    (debug "query" (pr-str sql-query))
    (seq
      (sql/with-db-connection [db-conn (:url (config))]
        (sql/query
          db-conn
          sql-query
          {:identifiers (or identifiers kebab)})))))

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

(defn entity-count
  [table]
  (-> (query table {:select/clause "COUNT(*) as count"})
      first
      :count))
