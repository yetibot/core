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
