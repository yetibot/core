(ns yetibot.core.test.db.agent-run
  (:require [midje.sweet :refer [fact => throws]]
            [clojure.java.jdbc :as sql]
            [yetibot.core.db :as database]
            [yetibot.core.db.util :as util]
            [yetibot.core.db.agent-run :as runs]))

(defn api [name & args]
  (if-let [f (ns-resolve 'yetibot.core.db.agent-run (symbol name))]
    (apply f args) ::missing))

(defn with-store [f]
  (let [config (util/config)
        prefix (str "u1_" (System/nanoTime) "_")]
    (with-redefs [util/config (constantly (assoc config :table {:prefix prefix}))]
      (try
        (database/idempotent-create-table! "agent_run" (:schema/specs runs/schema))
        (f (:url config))
        (finally (sql/db-do-commands (:url config)
                   (str "DROP TABLE " (util/qualified-table-name "agent_run"))))))))

(def allocation {:limit 2 :generation-cap 123})
(defn create-row [tx run-id]
  (api "create-inherited!" tx "agent_run" {:run-id run-id :request "private"}
       allocation))

(fact "real PostgreSQL admissions serialize the last unit and fence stale workers"
  (with-store
    (fn [url]
      (let [row (sql/with-db-transaction [tx url] (create-row tx "one"))
            claim (sql/with-db-transaction [tx url]
                    (api "claim!" tx "agent_run" (:id row) 0))]
        (map? row) => true
        (when (map? claim)
          (let [owner (:owner claim)
                a (sql/with-db-transaction [tx url]
                    (api "admit!" tx owner (:state-revision claim)
                         {:kind "command"}))
                races (doall (map deref
                              (repeatedly 2
                                #(future
                                   (try
                                     (sql/with-db-transaction [tx url]
                                       (api "admit!" tx owner (:revision a)
                                            {:kind "inference" :parent-ordinal (:ordinal a)}))
                                     (catch Exception _ :denied))))))]
            (count (filter map? races)) => 1
            (count (filter #{:denied} races)) => 1
            (sql/with-db-transaction [tx url]
              (api "admit!" tx (update owner :fence inc) nil {:kind "tool"}))
            => (throws Exception)
            ;; A subordinate receipt cannot release the outer intent.
            (let [child (first (filter map? races))]
              (sql/with-db-transaction [tx url]
                (api "finish-operation!" tx owner child :definitive))
              (:execution-phase (api "read-row" url "agent_run" (:id row)))
              => "operation-in-flight"
              (sql/with-db-transaction [tx url]
                (api "claim!" tx "agent_run" (:id row)
                     (:state-revision (api "read-row" tx "agent_run" (:id row)))))
              => #(= :unknown (:status %))
              (sql/with-db-transaction [tx url]
                (api "finish-operation!" tx owner a :definitive))
              => (throws Exception))))))))

(fact "child allocation and parent reservation commit or roll back together"
  (with-store
    (fn [url]
      (let [parent (sql/with-db-transaction [tx url] (create-row tx "parent"))]
        (when (map? parent)
          (try
            (sql/with-db-transaction [tx url]
              (api "transfer!" tx {:table "agent_run" :id (:id parent)}
                   "agent_run" {:run-id "rolled-back" :request "child"})
              (throw (Exception. "rollback")))
            (catch Exception _))
          (:execution-reserved (api "read-row" url "agent_run" (:id parent))) => 0
          (let [child (sql/with-db-transaction [tx url]
                        (api "transfer!" tx {:table "agent_run" :id (:id parent)}
                             "agent_run" {:run-id "child" :request "child"}))]
            (:execution-limit child) => 2
            (:execution-reserved (api "read-row" url "agent_run" (:id parent))) => 2
            (sql/with-db-transaction [tx url]
              (api "transfer!" tx {:table "agent_run" :id (:id parent)}
                   "agent_run" {:run-id "child2" :request "child"}))
            => #(zero? (:execution-limit %))))))))

(fact "safe nested receipts, checkpoints and legacy null rows survive real reconnects"
  (with-store
    (fn [url]
      (let [legacy (first (runs/create {:run-id "legacy" :request "legacy"}))
            row (sql/with-db-transaction [tx url]
                  (api "create-inherited!" tx "agent_run"
                       {:run-id "nested" :request "private" :execution-state "{}"}
                       {:limit 4 :generation-cap 123}))
            claim (sql/with-db-transaction [tx url]
                    (api "claim!" tx "agent_run" (:id row) 0))
            owner (:owner claim)
            admit (fn [parent]
                    (sql/with-db-transaction [tx url]
                      (let [current (api "read-row" tx "agent_run" (:id row))]
                        (api "admit!" tx owner (:state-revision current)
                             {:kind (if parent "inference" "command")
                              :parent-ordinal parent}))))
            outer (admit nil) inner (admit (:ordinal outer))
            nested (admit (:ordinal inner)) last-op (admit (:ordinal nested))]
        (:execution-kind (api "read-row" url "agent_run" (:id legacy))) => nil
        (doseq [op [last-op nested inner]]
          (sql/with-db-transaction [tx url]
            (api "finish-operation!" tx owner op :definitive))
          (:execution-phase (api "read-row" url "agent_run" (:id row)))
          => "operation-in-flight")
        (sql/with-db-transaction [tx url]
          (api "checkpoint!" tx owner (:state-revision
                       (api "read-row" tx "agent_run" (:id row))) "{}" "ready"))
        => (throws Exception)
        (sql/with-db-transaction [tx url]
          (api "complete-operation!" tx owner outer "{\"phase\":\"next\"}" "ready"))
        => #(= "ready" (:execution-phase %))
        (let [saved (api "read-row" url "agent_run" (:id row))]
          (:execution-used saved) => 4
          (sql/with-db-transaction [tx url]
            (api "admit!" tx owner (:state-revision saved) {:kind "inference"}))
          => (throws Exception)
          (sql/with-db-transaction [tx url]
            (api "checkpoint!" tx owner 0 "{}" "ready")) => (throws Exception)
          (sql/with-db-transaction [tx url]
            (api "checkpoint!" tx owner (:state-revision saved)
                 (str "\"" (apply str (repeat 2097152 "x")) "\"") "ready"))
          => (throws Exception)
          (:execution-state (api "read-row" url "agent_run" (:id row)))
          => "{\"phase\":\"next\"}")))))

(fact "concurrent durable child transfers cannot duplicate the remaining allowance"
  (with-store
    (fn [url]
      (let [parent (sql/with-db-transaction [tx url] (create-row tx "siblings"))
            children (doall
                       (map deref
                         (for [name ["sibling-a" "sibling-b"]]
                           (future
                             (sql/with-db-transaction [tx url]
                               (api "transfer!" tx {:table "agent_run" :id (:id parent)}
                                    "agent_run" {:run-id name :request "private"}))))))]
        (sort (map :execution-limit children)) => [0 2]
        (:execution-reserved (api "read-row" url "agent_run" (:id parent))) => 2
        (sql/with-db-transaction [tx url]
          (api "create-inherited!" tx "agent_run" {:run-id "sibling-a" :request "private"}
               allocation)) => (throws Exception)))))

(fact "missing inherited schema fails closed instead of using permissive legacy writes"
  (with-store
    (fn [url]
      (sql/db-do-commands url (str "ALTER TABLE " (util/qualified-table-name "agent_run")
                                  " DROP COLUMN worker_fence"))
      (sql/with-db-transaction [tx url] (create-row tx "no-schema"))
      => (throws Exception)
      (count (runs/find-all)) => 0)))

(def invalid-json-documents
  ["{} trailing" "{} {}" "{\"a\":1,}" "[1,]"
   "" " \n\t" "01" "NaN" "// comment\n{}"
   "{\"token\":\"SYNTHETIC-CHECKPOINT-SENTINEL\",}"])

(fact "checkpoints require one complete strict JSON document without exposing rejected state"
  (doseq [state invalid-json-documents]
    (api "bounded-json" state) => (throws Exception)
    (try (api "bounded-json" state)
         (catch Exception e (.getMessage e)))
    => "Private checkpoint must contain one valid JSON document.")
  (doseq [state [false true 42 {} []]]
    (api "bounded-json" state) => (throws Exception))
  (doseq [state ["{}" "[]" "\"text\"" "0" "-1.2e+3"
                "true" "false" "null" " \n {\"a\":[false,null,1]} \t"]]
    (identical? state (api "bounded-json" state)) => true))

(fact "invalid complete documents roll back inherited agent creation and checkpoints"
  (with-store
    (fn [url]
      (doseq [state invalid-json-documents]
        (let [before-count (count (runs/find-all))]
          (sql/with-db-transaction [tx url]
            (runs/create-inherited! tx "agent_run"
              {:run-id (str (java.util.UUID/randomUUID)) :request "private"
               :execution-state state} allocation)) => (throws Exception)
          (count (runs/find-all)) => before-count)
        (let [row (sql/with-db-transaction [tx url]
                    (create-row tx (str (java.util.UUID/randomUUID))))
              claim (sql/with-db-transaction [tx url]
                      (runs/claim! tx "agent_run" (:id row) 0))
              owner (:owner claim)
              before (runs/read-row url "agent_run" (:id row))]
          (sql/with-db-transaction [tx url]
            (runs/checkpoint! tx owner (:state-revision before) state "ready"))
          => (throws Exception)
          (runs/read-row url "agent_run" (:id row)) => before
          (let [operation (sql/with-db-transaction [tx url]
                            (let [current (runs/read-row tx "agent_run" (:id row))]
                              (runs/admit! tx owner (:state-revision current)
                                           {:kind "command"})))
                in-flight (runs/read-row url "agent_run" (:id row))]
            (sql/with-db-transaction [tx url]
              (runs/complete-operation! tx owner operation state "ready"))
            => (throws Exception)
            (runs/read-row url "agent_run" (:id row)) => in-flight))))))

(fact "the shared checkpoint JSON validator preserves the syntax and UTF-8 byte contract"
  (api "bounded-json" nil) => nil
  (api "bounded-json" "not-json") => (throws Exception)
  (let [boundary (str "\"" (apply str (repeat 1048575 "é")) "\"")]
    (alength (.getBytes boundary "UTF-8")) => 2097152
    (api "bounded-json" boundary) => boundary
    (api "bounded-json" (str " " boundary)) => (throws Exception)))
