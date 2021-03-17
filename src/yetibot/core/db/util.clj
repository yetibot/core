(ns yetibot.core.db.util
  (:require
    [clojure.set :refer [union]]
    [clojure.spec.alpha :as s]
    [clojure.string :refer [blank? join split]]
    [cuerdas.core :refer [kebab snake]]
    [clojure.java.jdbc :as sql]
    [taoensso.timbre :refer [info color-str]]
    [yetibot.core.config :refer [get-config]]))

(s/def ::url string?)

(s/def ::prefix string?)

(s/def ::table (s/keys :req-un [::prefix]))

(s/def ::db-config (s/keys :req-un [::url]
                           :opt-un [::table]))

(def default-db-url "postgresql://localhost:5432/yetibot")

(def default-table-prefix "yetibot_")

(defn default-fields
  "All tables get these fields by default"
  []
  [[:id :serial "PRIMARY KEY"]
   [:created-at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"]])

(comment
  (default-fields)
  )

(defn config []
  (merge
    ;; default
    {:url default-db-url
     :table {:prefix default-table-prefix}}
    (:value (get-config ::db-config [:db]))))

(comment
  (config)
  )

(defn qualified-table-name
  [table-name]
  (str (-> (config) :table :prefix) table-name))

(comment
  (qualified-table-name "hello")
  )

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

(comment
  (transform-where-map {:foo "bar"})
  (transform-where-map {:foo "bar" :bar "baz"})
  (transform-where-map {})
  )

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

(def merge-fn
  "Merge functions for specific keys supported by `query`"
  {:select/clause (fn [x y]
                    (join ", " (concat (split x #",\s*") (split y #",\s*"))))
   :where/clause (fn [x y] (str x " AND " y))})

(defn merge-queries
  [& qs]
  (let [ks (reduce union (map (comp set keys) qs))]
    (reduce
     (fn [acc i]
       (into acc
             (for [k ks
                   :let [left (k acc)
                         right (k i)]
                   :when (or (k acc) (k i))]
               [k
                (cond
                  ;; both - merge them
                  (and left right) ((get merge-fn k into) (k acc) (k i))
                  ;; left only
                  (and left (not right)) left
                  ;; right only
                  (and right (not left)) right)])))
     {}
     qs)))

(defn generate-sql-query
  "Generates SQL query string based on table and query-map args.
   Allows us to seperate the generation of the SQL query and the
   execution of said query"
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
          limit-clause :limit/clause}]
  (let [select-clause (or select-clause "*")
        [where-clause where-args] (combine-wheres
                                   (transform-where-map where-map)
                                   [where-clause where-args])]
    (into [(str "SELECT " select-clause
                " FROM " (qualified-table-name table)
                (when-not (blank? where-clause)
                  (str " WHERE " where-clause))
                (when group-clause (str " GROUP BY " group-clause))
                (when having-clause (str " HAVING " having-clause))
                (when order-clause (str " ORDER BY " order-clause))
                (when offset-clause (str " OFFSET " offset-clause))
                (when limit-clause (str " LIMIT " limit-clause)))]
          where-args)))

(comment
  (generate-sql-query
   "hello"
   {:select/clause "*"
    :where/map {:id 123}
    :where/clause "is_awesome = ?"
    :where/args [true]
    :group/clause "id"
    :having/clause "SUM(points) > 0"
    :order-by "id"
    :offset/clause 10
    :limit/clause 1})
  (generate-sql-query "hello" {:select/clause "COUNT(*) as count"})
  )

(defn query
  "SELECT query of table arg, allowing for complex WHERE clauses that contain
   predicates and/or expressions, based on provided query-map arg."
  [table query-map]
  (let [sql-query (generate-sql-query table query-map)
        identifiers (:query/identifiers query-map)]
    (info "db query" (color-str :blue (pr-str sql-query)))
    (seq
     (sql/with-db-connection
      [db-conn (:url (config))]
      (sql/query db-conn
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
