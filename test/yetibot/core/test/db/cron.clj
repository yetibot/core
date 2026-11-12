(ns yetibot.core.test.db.cron
  (:require [midje.sweet :refer [fact => throws]]
            [clojure.java.jdbc :as sql]
            [cuerdas.core :refer [snake]]
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

(defn with-cron-store [f]
  (support/with-store
    (fn [url]
      (try
        (database/idempotent-create-table! "cron" (:schema/specs cron/schema))
        (f url)
        (finally
          (sql/db-do-commands url
            (str "DROP TABLE IF EXISTS " (util/qualified-table-name "cron"))))))))

(def invalid-states
  ["not-json"
   (str "\"" (apply str (repeat 2097151 "x")) "\"")
   ;; Under the character limit, over the UTF-8 byte limit.
   (str "\"" (apply str (repeat 1048576 "é")) "\"")])

(fact "inherited cron final state rejection rolls back SQL rows and parent reservations"
  (with-cron-store
    (fn [url]
      (doseq [state invalid-states mode [:create :conversion :sticky]
              writer [:inherit :prepare]]
        (let [parent (sql/with-db-transaction [tx url]
                       (support/create-row tx (str (java.util.UUID/randomUUID))))
              owner {:table "agent_run" :id (:id parent)}
              existing (case mode
                         :create nil
                         :conversion (first (cron/create entity))
                         :sticky (sql/with-db-transaction [tx url]
                                   (cron/inherit! tx owner nil
                                     (assoc entity :execution-state "{}"))))
              before-parent (runs/read-row url "agent_run" (:id parent))
              before-rows (sort-by :id (cron/find-all))]
          (with-redefs-fn {(ns-resolve 'yetibot.core.util.agent-execution 'host)
                           (atom {:callbacks
                                  {:write-cron
                                   (fn [tx locked data _]
                                     ;; First produce valid inherited attrs, then
                                     ;; override the FINAL state as a host writer can.
                                     (let [attrs (cron/inherit-attrs! tx owner locked
                                                   (assoc data :execution-state "{}"))]
                                       ;; Native SQL in this same transaction must
                                       ;; roll back too, not only the reservation.
                                       (sql/insert! tx (util/qualified-table-name "cron") entity)
                                       (assoc attrs :execution-state state)))}})}
            (fn []
              (sql/with-db-transaction [tx url]
                (if (= writer :inherit)
                  (cron/inherit! tx owner existing (assoc entity :execution-state state))
                  (let [attrs (cron/prepare-write! tx existing entity)]
                    (if existing
                      (sql/update! tx (util/qualified-table-name "cron") attrs
                                   ["id=?" (:id existing)] {:entities snake})
                      (sql/insert! tx (util/qualified-table-name "cron") attrs
                                   {:entities snake})))))
              => (throws Exception)))
          (runs/read-row url "agent_run" (:id parent)) => before-parent
          (sort-by :id (cron/find-all)) => before-rows)))))

(fact "inherited cron accepts exact UTF-8 boundary, sticky retained state and null state"
  (with-cron-store
    (fn [url]
      (doseq [state [nil "{}" (str "\"" (apply str (repeat 1048575 "é")) "\"")]]
        (let [parent (sql/with-db-transaction [tx url]
                       (support/create-row tx (str (java.util.UUID/randomUUID))))
              row (sql/with-db-transaction [tx url]
                    (cron/inherit! tx {:table "agent_run" :id (:id parent)} nil
                                   (assoc entity :execution-state state)))]
          (:execution-state (runs/read-row url "cron" (:id row))) => state
          (cron/update-where {:id (:id row)} {:cmd "echo changed"}) => [1]
          (:execution-state (runs/read-row url "cron" (:id row))) => state
          (:execution-limit (runs/read-row url "cron" (:id row))) => 2)))))

(fact "sticky inherited edits validate retained state but legacy rows keep their old contract"
  (with-cron-store
    (fn [url]
      (let [parent (sql/with-db-transaction [tx url] (support/create-row tx "retained-state"))
            row (sql/with-db-transaction [tx url]
                  (cron/inherit! tx {:table "agent_run" :id (:id parent)} nil entity))
            legacy (first (cron/create (assoc entity :execution-state "legacy not-json")))]
        ;; Characterize old inherited rows admitted before the shared guard.
        (sql/update! url (util/qualified-table-name "cron")
                     {:execution_state "not-json"} ["id=?" (:id row)])
        (let [before (runs/read-row url "cron" (:id row))]
          (cron/update-where {:id (:id row)} {:cmd "echo changed"}) => (throws Exception)
          (runs/read-row url "cron" (:id row)) => before
          (sql/with-db-transaction [tx url]
            (cron/inherit! tx {:table "agent_run" :id (:id parent)} before
                           {:cmd "echo changed"})) => (throws Exception)
          (runs/read-row url "cron" (:id row)) => before)
        (cron/update-where {:id (:id legacy)} {:cmd "echo legacy"}) => [1]
        (:execution-state (runs/read-row url "cron" (:id legacy))) => "legacy not-json"))))
