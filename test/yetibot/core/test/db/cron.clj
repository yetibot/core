(ns yetibot.core.test.db.cron
  (:require [midje.sweet :refer [fact => throws]]
            [clojure.java.jdbc :as sql]
            [yetibot.core.db :as database]
            [yetibot.core.db.util :as util]
            [yetibot.core.db.cron :as cron]
            [yetibot.core.commands.cron :as commands]
            [yetibot.core.handler :as handler]
            [hara.io.scheduler :as scheduler]
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

(fact "an edit during a tick invalidates the worker and cannot clear its uncertainty"
  (support/with-store
    (fn [url]
      (try
        (database/idempotent-create-table! "cron" (:schema/specs cron/schema))
        (let [parent (sql/with-db-transaction [tx url] (support/create-row tx "edit-parent"))
              row (sql/with-db-transaction [tx url]
                    (cron/inherit! tx {:table "agent_run" :id (:id parent)} nil entity))
              claim (sql/with-db-transaction [tx url] (cron/claim-tick! tx (:id row)))]
          (cron/update-where {:id (:id row)} {:schedule "0 0 9 * * * *"}) => [1]
          (let [updated (runs/read-row url "cron" (:id row))]
            (:execution-phase updated) => "unknown"
            (:execution-used updated) => 1
            (> (:worker-fence updated) (get-in claim [:owner :fence])) => true
            (sql/with-db-transaction [tx url]
              (runs/finish-operation! tx (:owner claim) (:operation claim) :definitive))
            => (throws Exception)
            (sql/with-db-transaction [tx url] (cron/claim-tick! tx (:id row)))
            => {:status :unknown}))
        (finally (sql/db-do-commands url
                   (str "DROP TABLE IF EXISTS " (util/qualified-table-name "cron"))))))))

(fact "safe occurrences and restart reads do not replenish an inherited schedule"
  (support/with-store
    (fn [url]
      (try
        (database/idempotent-create-table! "cron" (:schema/specs cron/schema))
        (let [parent (sql/with-db-transaction [tx url] (support/create-row tx "repeat-parent"))
              row (sql/with-db-transaction [tx url]
                    (cron/inherit! tx {:table "agent_run" :id (:id parent)} nil entity))]
          (dotimes [_ 2]
            (let [claim (sql/with-db-transaction [tx url] (cron/claim-tick! tx (:id row)))]
              (sql/with-db-transaction [tx url]
                (runs/complete-operation! tx (:owner claim) (:operation claim) "{}" "ready"))))
          (:execution-used (runs/read-row url "cron" (:id row))) => 2
          (sql/with-db-transaction [tx url] (cron/claim-tick! tx (:id row)))
          => {:status :exhausted}
          (:execution-limit (runs/read-row url "cron" (:id row))) => 2)
        (finally (sql/db-do-commands url
                   (str "DROP TABLE IF EXISTS " (util/qualified-table-name "cron"))))))))

(fact "active core writer classifies and reserves inside the same real SQL transaction"
  (support/with-store
    (fn [url]
      (try
        (database/idempotent-create-table! "cron" (:schema/specs cron/schema))
        (let [parent (sql/with-db-transaction [tx url] (support/create-row tx "writer-parent"))
              calls (atom [])]
          (with-redefs-fn {(ns-resolve 'yetibot.core.util.agent-execution 'host) (atom nil)}
            (fn []
              (execution/install-host! :writer
                {:write-cron (fn [tx existing data origin]
                               (swap! calls conj [existing origin])
                               (cron/inherit-attrs! tx
                                 {:table "agent_run" :id (:id parent)} existing data))})
              (let [row (first (cron/create entity))]
                (:execution-origin row) => "app-inherited-v1"
                (:execution-limit row) => 2
                @calls => [[nil nil]]
                (:execution-reserved (runs/read-row url "agent_run" (:id parent))) => 2)
              ;; A missing classifier under an active bridge cannot store a
              ;; compatibility/null marker, even for an otherwise normal row.
              (execution/install-host! :writer {})
              (cron/create entity) => (throws Exception))))
        (finally (sql/db-do-commands url
                   (str "DROP TABLE IF EXISTS " (util/qualified-table-name "cron"))))))))


(fact "a callback wired for an uncommitted then rolled-back row cannot run as legacy"
  (support/with-store
    (fn [url]
      (try
        (database/idempotent-create-table! "cron" (:schema/specs cron/schema))
        (let [callback (atom nil) effects (atom 0)
              parent (sql/with-db-transaction [tx url] (support/create-row tx "rollback-task"))]
          (with-redefs [scheduler/delete-task (fn [& _])
                        scheduler/add-task (fn [_ _ task] (reset! callback (:handler task)))
                        handler/record-and-run-raw (fn [& _] (swap! effects inc) [])]
            (try
              (sql/with-db-transaction [tx url]
                (let [row (cron/inherit! tx {:table "agent_run" :id (:id parent)} nil entity)]
                  (commands/wire-cron! row)
                  (@callback nil)
                  (throw (Exception. "registration rollback"))))
              (catch Exception _))
            (@callback nil)
            @effects => 0
            (:execution-reserved (runs/read-row url "agent_run" (:id parent))) => 0
            (count (cron/find-all)) => 0))
        (finally (sql/db-do-commands url
                   (str "DROP TABLE IF EXISTS " (util/qualified-table-name "cron"))))))))
