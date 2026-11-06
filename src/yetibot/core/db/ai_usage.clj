(ns yetibot.core.db.ai-usage
  "Private minimal AI ledger, with bounded transactions and no content fields."
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [yetibot.core.db.util :as db])
  (:import [java.sql Timestamp]
           [java.time Instant]
           [org.postgresql.util PGobject]))

(def coverage-id "00000000-0000-0000-0000-000000000000")

(def schema
  {:schema/table "ai_usage"
   :schema/specs
   [[:event-id "varchar(36)" "PRIMARY KEY"]
    [:context-id "varchar(36)"]
    [:parent-id "varchar(36)"]
    [:event-kind :text "NOT NULL"]
    [:provider :text "NOT NULL"]
    [:operation :text "NOT NULL"]
    [:started-at :timestamptz "NOT NULL DEFAULT CURRENT_TIMESTAMP"]
    [:finished-at :timestamptz]
    [:state :text "NOT NULL"]
    [:http-status :integer]
    [:model-usage :jsonb "NOT NULL"]]})

(defn- table [] (db/qualified-table-name "ai_usage"))

(defn- transaction! [read-only? f]
  (let [url (:url (db/config))
        url (str url (if (.contains ^String url "?") "&" "?")
                 "loginTimeout=2&socketTimeout=3")]
    (jdbc/with-db-connection [connection url]
      (jdbc/with-db-transaction [tx connection {:read-only? read-only?
                                             :isolation :repeatable-read}]
        (jdbc/execute! tx ["SET LOCAL statement_timeout = '2000ms'"])
        (jdbc/execute! tx ["SET LOCAL lock_timeout = '1000ms'"])
        (when-not read-only?
          (jdbc/execute! tx ["SET LOCAL synchronous_commit = 'on'"]))
        (f tx)))))

(defn- jsonb [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string value))))

(defn start!
  "Persist first rollout coverage, before adapters or background AI start."
  []
  (transaction! false
    (fn [tx]
      (jdbc/execute! tx
        [(str "CREATE INDEX IF NOT EXISTS " (table) "_started_at_idx ON "
              (table) " (started_at)")])
      (jdbc/execute! tx
        [(str "INSERT INTO " (table)
              " (event_id,event_kind,provider,operation,state,model_usage)"
              " VALUES (?,'coverage','system','tracking','coverage','[]'::jsonb)"
              " ON CONFLICT (event_id) DO NOTHING") coverage-id]))))

(defn begin! [{:keys [event-id context-id parent-id provider operation
                     event-kind model-usage]}]
  (transaction! false
    #(jdbc/execute! %
       [(str "INSERT INTO " (table)
             " (event_id,context_id,parent_id,event_kind,provider,operation,"
             "state,model_usage) VALUES (?,?,?,?,?,?,'pending',?)")
        event-id context-id parent-id event-kind provider operation
        (jsonb model-usage)])))

(defn finish! [id {:keys [state http-status model-usage]}]
  (transaction! false
    #(jdbc/execute! %
       [(str "UPDATE " (table)
             " SET finished_at=CURRENT_TIMESTAMP,state=?,http_status=?,"
             "model_usage=? WHERE event_id=? AND state='pending'")
        state http-status (jsonb model-usage) id])))

(def ^:private aggregates
  (str
    "COUNT(DISTINCT event_id) AS events,"
    "COUNT(DISTINCT event_id) FILTER (WHERE event_kind='request') AS http_attempts,"
    "COUNT(DISTINCT event_id) FILTER (WHERE event_kind='session') AS cli_sessions,"
    "COUNT(DISTINCT event_id) FILTER (WHERE state='failed') AS failed_events,"
    "COUNT(DISTINCT event_id) FILTER (WHERE state IN ('pending','unknown'))"
    " AS uncertain_events,"
    "COALESCE(SUM((u->>'requests')::numeric) FILTER"
    " (WHERE event_kind='request'),0) AS http_requests,"
    "COALESCE(SUM((u->>'requests')::numeric) FILTER"
    " (WHERE event_kind='session'),0) AS cli_requests,"
    "COUNT(DISTINCT event_id) FILTER (WHERE u->>'requests' IS NULL)"
    " AS request_count_unknown_events,"
    "COALESCE(SUM((u->>'usd')::numeric) FILTER"
    " (WHERE u->>'cost_kind'='reported'),0) AS reported_usd,"
    "COALESCE(SUM((u->>'usd')::numeric) FILTER"
    " (WHERE u->>'cost_kind'='estimate'),0) AS estimated_usd,"
    "COUNT(DISTINCT event_id) FILTER (WHERE u->>'usd' IS NULL)"
    " AS cost_unknown_events,"
    "SUM((u->>'input_tokens')::numeric) AS input_tokens,"
    "SUM((u->>'output_tokens')::numeric) AS output_tokens,"
    "SUM((u->>'uncached_tokens')::numeric) AS uncached_tokens,"
    "SUM((u->>'cached_tokens')::numeric) AS cached_tokens,"
    "SUM((u->>'reasoning_tokens')::numeric) AS reasoning_tokens,"
    "SUM((u->>'neurons')::numeric) AS neurons,"
    "SUM((u->>'images')::numeric) AS images,"
    "SUM((u->>'seconds')::numeric) AS seconds,"
    "COUNT(DISTINCT event_id) FILTER (WHERE operation IN"
    " ('text','classification','agent') AND"
    " (u->>'input_tokens' IS NULL OR u->>'output_tokens' IS NULL))"
    " AS token_unknown_events"))

(defn- timestamp-ceiling [^Instant instant]
  ;; PostgreSQL stores microseconds. Ceiling both bounds preserves >= / <
  ;; comparisons even when the caller supplies nanosecond instants.
  (let [remainder (mod (.getNano instant) 1000)]
    (Timestamp/from (if (zero? remainder) instant
                     (.plusNanos instant (- 1000 remainder))))))

(defn- integral-counts [row]
  (reduce (fn [result key]
            (update result key
              #(when (some? %)
                 (bigint (.toBigIntegerExact ^java.math.BigDecimal %)))))
          row [:http_requests :cli_requests :input_tokens :output_tokens
               :uncached_tokens :cached_tokens :reasoning_tokens :images :seconds]))

(defn report!
  "One read-only repeatable-read snapshot; no raw events or actor identities.
   Global totals include all groups, even when the model breakdown is bounded."
  [^Instant start ^Instant end]
  (transaction! true
    (fn [tx]
      (let [cte (str "WITH events AS (SELECT * FROM " (table)
                     " WHERE event_kind IN ('request','session')"
                     " AND started_at>=? AND started_at<?),"
                     "expanded AS (SELECT e.*,m.u FROM events e"
                     " CROSS JOIN LATERAL jsonb_array_elements(e.model_usage)"
                     " AS m(u)) ")
            bounds [(timestamp-ceiling start) (timestamp-ceiling end)]
            coverage (first (jdbc/query tx
                              [(str "SELECT started_at FROM " (table)
                                    " WHERE event_id=?") coverage-id]))
            totals (first (jdbc/query tx
                            (into [(str cte "SELECT " aggregates " FROM expanded")]
                                  bounds)))
            groups (jdbc/query tx
                     (into [(str cte "SELECT provider,u->>'model' AS model,"
                                 "event_kind AS scope," aggregates
                                 " FROM expanded GROUP BY "
                                 "provider,u->>'model',event_kind"
                                 " ORDER BY provider,model,scope LIMIT 201")]
                           bounds))]
        {:coverage_started_at (:started_at coverage)
         :totals (integral-counts totals)
         :groups (mapv integral-counts (take 200 groups))
         :groups_truncated (> (count groups) 200)}))))
