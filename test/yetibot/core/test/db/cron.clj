(ns yetibot.core.test.db.cron
  (:require [midje.sweet :refer [fact => throws]]
            [clojure.java.jdbc :as sql]
            [yetibot.core.db :as database]
            [yetibot.core.db.util :as util]
            [yetibot.core.db.cron :as cron]
            [yetibot.core.db.agent-run :as runs]
            [yetibot.core.test.db.agent-run :as support]
            [yetibot.core.util.agent-execution :as execution]))

(defn api [name & args]
  (if-let [f (ns-resolve 'yetibot.core.db.cron (symbol name))]
    (apply f args) ::missing))
(def entity {:schedule "0 0 8 * * * *" :cmd "agent work"})

(fact "cron null origins remain unclassified, private grants never public"
  (api "origin-kind" {}) => :legacy-unclassified
  (api "origin-kind" {:execution-origin "bad"}) => :invalid
  (api "origin-kind" {:execution-origin "app-inherited-v1"}) => :inherited
  (api "public-row" (assoc entity :execution-state "private" :execution-limit 20))
  => entity)

(fact "core-only new cron writes store explicit compatibility, never null/human"
  (support/with-store
    (fn [url]
      (try
        (database/idempotent-create-table! "cron" (:schema/specs cron/schema))
        (let [row (first (cron/create entity))]
          (api "origin-kind" row) => :compatibility-unclassified)
        (finally (sql/db-do-commands url
                   (str "DROP TABLE IF EXISTS " (util/qualified-table-name "cron"))))))))

(fact "real cron transfers, sticky edits, and repeated ticks share one finite ledger"
  (support/with-store
    (fn [url]
      (try
        (database/idempotent-create-table! "cron" (:schema/specs cron/schema))
        (let [parent (sql/with-db-transaction [tx url] (support/create-row tx "cron-parent"))
              row (sql/with-db-transaction [tx url]
                    (api "inherit!" tx {:table "agent_run" :id (:id parent)} nil entity))]
          (map? row) => true
          (when (map? row)
            (:execution-limit row) => 2
            (:execution-reserved (runs/read-row url "agent_run" (:id parent))) => 2
            (sql/with-db-transaction [tx url]
              (api "prepare-write!" tx row (assoc entity :execution-origin "human-literal-v1")))
            => #(= "app-inherited-v1" (:execution-origin %))
            (let [claim (sql/with-db-transaction [tx url]
                          (api "claim-tick!" tx (:id row)))
                  owner (:owner claim) handle (:operation claim)]
              (:execution-used (runs/read-row url "cron" (:id row))) => 1
              (sql/with-db-transaction [tx url]
                (api "claim-tick!" tx (:id row))) => #(= :unknown (:status %))
              (sql/with-db-transaction [tx url]
                (runs/finish-operation! tx owner handle :definitive)) => (throws Exception))))
        (finally (sql/db-do-commands url
                   (str "DROP TABLE IF EXISTS " (util/qualified-table-name "cron"))))))))
