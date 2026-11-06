(ns yetibot.core.test.util.ai-usage-report
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.core.db.ai-usage :as store]
            [yetibot.core.db.util :as db]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.core.test.support.ai-usage :refer [attrs with-store]])
  (:import [java.time Instant]
           [java.sql Timestamp]))

(defn report [start end]
  (if-let [f (resolve 'yetibot.core.db.ai-usage/report!)]
    (f (Instant/parse start) (Instant/parse end)) ::unimplemented))

(defn seed! [rows table model date projection]
  (let [before (set (map :event_id (rows)))]
    (usage/capture! (assoc attrs :model model
                                :operation (if (= model "session") "agent" "text"))
                    (constantly :answer) (constantly projection))
    (let [id (:event_id (first (remove #(before (:event_id %)) (rows))))]
      (jdbc/execute! (:url (db/config))
        [(str "UPDATE " table " SET started_at=? WHERE event_id=?")
         (Timestamp/from (Instant/parse date)) id]))))

(fact "UTC ranges include start/exclude end and sessions never double event counts"
  (with-store
    (fn [rows table]
      (store/start!)
      (seed! rows table "before" "2026-10-31T23:59:59Z"
             {:usd 5M :cost_kind "reported"})
      (seed! rows table "included" "2026-11-01T00:00:00Z"
             {:usd 1M :cost_kind "reported" :input_tokens 10 :output_tokens 3})
      (seed! rows table "session" "2026-11-15T00:00:00Z"
             {:models [{:model "m1" :requests 2 :input_tokens 5 :output_tokens 2}
                       {:model "m2" :requests 3 :input_tokens 8 :output_tokens 4}]})
      (seed! rows table "after" "2026-12-01T00:00:00Z"
             {:usd 2M :cost_kind "estimate"})
      (with-redefs [store/finish! (fn [& _] (throw (Exception. "PRIVATE")))]
        (seed! rows table "pending" "2026-11-20T00:00:00Z" {}))
      (let [result (report "2026-11-01T00:00:00Z" "2026-12-01T00:00:00Z")]
        (select-keys (:totals result)
          [:events :http_attempts :cli_sessions :uncertain_events
           :http_requests :cli_requests :request_count_unknown_events
           :cost_unknown_events :reported_usd :estimated_usd
           :input_tokens :output_tokens :token_unknown_events])
        => {:events 3 :http_attempts 2 :cli_sessions 1 :uncertain_events 1
            :http_requests 1 :cli_requests 5 :request_count_unknown_events 1
            :cost_unknown_events 2 :reported_usd 1M :estimated_usd 0M
            :input_tokens 23 :output_tokens 9 :token_unknown_events 1}
        (count (:groups result)) => 4
        (:groups_truncated result) => false
        (some? (:coverage_started_at result)) => true
        (string/includes? (pr-str result) "event_id") => false
        (string/includes? (pr-str result) "PRIVATE") => false))))

(fact "nanosecond bounds preserve exact comparisons against stored microseconds"
  (with-store
    (fn [rows table]
      (seed! rows table "point" "2026-11-01T00:00:00Z" {})
      (get-in (report "2026-11-01T00:00:00.000000001Z"
                      "2026-11-01T00:00:00.000000002Z") [:totals :events]) => 0
      (get-in (report "2026-10-31T23:59:59.999999999Z"
                      "2026-11-01T00:00:00.000000001Z") [:totals :events]) => 1)))

(fact "group limits do not truncate global cost or event totals"
  (with-store
    (fn [_ table]
      (jdbc/execute! (:url (db/config))
        [(str "INSERT INTO " table
              " (event_id,event_kind,provider,operation,started_at,state,model_usage)"
              " SELECT lpad(i::text,36,'0'),'request','fixture','text',"
              " '2026-11-02T00:00:00Z'::timestamptz,'completed',"
              " jsonb_build_array(jsonb_build_object('model','m'||i,"
              " 'requests',1,'usd',1,'cost_kind','reported'))"
              " FROM generate_series(1,205) AS i")])
      (let [result (report "2026-11-01T00:00:00Z" "2026-12-01T00:00:00Z")]
        (count (:groups result)) => 200
        (:groups_truncated result) => true
        (get-in result [:totals :events]) => 205
        (get-in result [:totals :reported_usd]) => 205M))))

(fact "summary and groups use one snapshot even when a call finishes meanwhile"
  (with-store
    (fn [rows table]
      (store/start!)
      (with-redefs [store/finish! (fn [& _] (throw (Exception. "fixture")))]
        (seed! rows table "pending" "2026-11-02T00:00:00Z" {}))
      (let [id (:event_id (first (filter #(= "pending" (:state %)) (rows))))
            query jdbc/query
            changed (atom false)]
        (with-redefs
          [jdbc/query
           (fn [connection sql & options]
             (let [result (apply query connection sql options)]
               (when (and (string/includes? (first sql) "WHERE event_id=?")
                          (compare-and-set! changed false true))
                 (store/finish! id {:state "completed"
                                   :model-usage [{:model "pending" :requests 1
                                                  :usd 1 :cost_kind "reported"}]}))
               result))]
          (let [result (report "2026-11-01T00:00:00Z" "2026-12-01T00:00:00Z")]
            @changed => true
            (get-in result [:totals :uncertain_events]) => 1
            (get-in result [:groups 0 :uncertain_events]) => 1))
        (:state (first (filter #(= id (:event_id %)) (rows)))) => "completed"))))
