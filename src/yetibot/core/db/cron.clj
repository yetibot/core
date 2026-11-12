(ns yetibot.core.db.cron
  (:require
    [yetibot.core.db.util :as db.util]
    [clojure.java.jdbc :as sql]
    [cuerdas.core :refer [snake]]
    [yetibot.core.db.agent-run :as runs]
    [yetibot.core.util.agent-execution :as execution]))

(def schema
  {:schema/table "cron"
   :schema/specs (into [[:chat-source-adapter :text]
                        [:chat-source-room :text]
                        [:user-id :text]
                        [:schedule :text "NOT NULL"]
                        [:cmd :text "NOT NULL"]
                        [:execution-origin :text]]
                       (concat runs/execution-columns (db.util/default-fields)))})

(declare create)

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(declare update-where)

(def entity-count (partial db.util/entity-count (:schema/table schema)))


(defn origin-kind [row]
  (case (:execution-origin row)
    nil :legacy-unclassified
    "compatibility-unclassified-v1" :compatibility-unclassified
    "human-literal-v1" :human-literal
    "app-inherited-v1" :inherited
    "untrusted-v1" :untrusted
    :invalid))

(defn public-row [row]
  (select-keys row [:id :created-at :chat-source-adapter :chat-source-room
                    :user-id :schedule :cmd]))

(defn- sticky-attrs [existing entity]
  (merge entity
    (select-keys existing
      (map first (remove #(= :execution-state (first %)) runs/execution-columns)))
    {:execution-origin "app-inherited-v1"
     :execution-state (or (:execution-state entity) (:execution-state existing))
     :state-revision (inc (:state-revision existing))
     :worker-fence (inc (:worker-fence existing))
     :execution-phase (if (= "operation-in-flight" (:execution-phase existing))
                        "unknown" (:execution-phase existing))}))

(defn inherit-attrs!
  "[tx parent existing entity] -> private SQL attrs. Reserve once on creation
   or conversion, in the SAME transaction as native INSERT/UPDATE. Already
   inherited rows keep all counters/fences/uncertainty; host narrows the codec."
  [tx parent existing entity]
  (runs/require-transaction! tx)
  (let [existing (when existing (runs/lock-row! tx "cron" (:id existing)))]
    (if (= :inherited (origin-kind existing))
      (sticky-attrs existing entity)
      (merge entity (runs/allocation-attrs (runs/reserve-remaining! tx parent))
             {:execution-origin "app-inherited-v1"
              :state-revision (if existing (inc (:state-revision existing)) 0)
              :worker-fence (if existing (inc (:worker-fence existing)) 0)}))))

(defn prepare-write!
  "[tx existing entity] -> classified attrs for BOTH core and native writers.
   Reload/lock existing inside tx. An active host MUST classify every write.
   No public map can mint a grant. Sticky inherited counters never reset on edit.
   Native version checks must include returned :state-revision; task rollback
   must reload committed rows, not reconstruct an old command-only closure."
  [tx existing entity]
  (runs/require-transaction! tx)
  (let [existing (when existing (runs/lock-row! tx "cron" (:id existing)))
        attrs (if (or (execution/host-active?) (execution/value-origin))
                (if-let [write (execution/host-callback :write-cron)]
                  (write tx existing entity (execution/value-origin))
                  (throw (ex-info "Scheduler origin host unavailable." {})))
                (assoc entity :execution-origin "compatibility-unclassified-v1"))
        attrs (if (= :inherited (origin-kind existing))
                (sticky-attrs existing attrs)
                attrs)]
    (when (or (#{:legacy-unclassified :invalid} (origin-kind attrs))
              (and (execution/host-active?)
                   (= :compatibility-unclassified (origin-kind attrs))))
      (throw (ex-info "Invalid new scheduler origin." {})))
    (when (= :inherited (origin-kind attrs))
      (runs/verify-schema! tx "cron")
      (when-not (and (= runs/inherited-kind (:execution-kind attrs))
                     (integer? (:execution-limit attrs))
                     (<= 0 (:execution-limit attrs) 128)
                     (integer? (:generation-cap attrs))
                     (<= 1 (:generation-cap attrs) 262144))
        (throw (ex-info "Inherited schedule has no finite grant." {}))))
    attrs))

(defn create [entity]
  (sql/with-db-transaction [tx (:url (db.util/config))]
    (let [attrs (prepare-write! tx nil entity)
          row (first (sql/insert! tx (db.util/qualified-table-name "cron") attrs
                                  {:entities snake}))]
      [(runs/read-row tx "cron" (:id row))])))

(defn update-where [where-map entity]
  (sql/with-db-transaction [tx (:url (db.util/config))]
    (let [[clause args] (db.util/transform-where-map where-map)
          rows (sql/query tx (into [(str "SELECT id FROM "
                                         (db.util/qualified-table-name "cron")
                                         " WHERE " clause " FOR UPDATE")] args))]
      [(reduce + 0
         (for [row rows]
           (let [attrs (prepare-write! tx row entity)]
             (first (sql/update! tx (db.util/qualified-table-name "cron")
                                 attrs ["id=?" (:id row)] {:entities snake})))))])))

(defn inherit!
  "Convenience core/host create or convert. Native writers use inherit-attrs!
   within their existing advisory-lock/version/idempotency transaction."
  [tx parent existing entity]
  (let [attrs (inherit-attrs! tx parent existing entity)]
    (if existing
      (do (sql/update! tx (db.util/qualified-table-name "cron") attrs
                       ["id=?" (:id existing)] {:entities snake})
          (runs/read-row tx "cron" (:id existing)))
      (let [row (first (sql/insert! tx (db.util/qualified-table-name "cron") attrs
                                  {:entities snake}))]
        (runs/read-row tx "cron" (:id row))))))

(defn claim-tick!
  "Reload the committed row under lock at EACH occurrence. Claim + one outer
   tick admission are atomic. Unknown/in-flight never replay; exhaustion never
   creates an allowance. Host restores real identity before executing expression."
  [tx id]
  (let [row (runs/lock-row! tx "cron" id)]
    (when-not (= :inherited (origin-kind row))
      (throw (ex-info "Tick is not an inherited schedule." {})))
    (cond
      (= "unknown" (:execution-phase row)) {:status :unknown}
      (= "operation-in-flight" (:execution-phase row))
      (runs/claim! tx "cron" id (:state-revision row))
      (>= (+ (:execution-used row) (:execution-reserved row)) (:execution-limit row))
      {:status :exhausted}
      :else
      (let [claim (runs/claim! tx "cron" id (:state-revision row))
            operation (runs/admit! tx (:owner claim) (:state-revision claim)
                                   {:kind "tick"})]
        (assoc claim :operation operation :state-revision (:revision operation))))))
