(ns yetibot.core.db.agent-run
  "Legacy in-flight CLI rows retain their original session/restart semantics.
   Nullable execution-kind separates inherited rows; unknown non-null kinds
   must never enter CLI resume. Inherited private JSON codecs/identity/policy
   belong to the host; core bounds JSON to two MiB and owns atomic ledgers.

   Mutators below take the caller's JDBC transaction, never a new connection:
   create-inherited! [tx table entity allocation] -> row (schedule after commit);
   claim! [tx table id revision] -> row+owner or {:status :unknown};
   admit! [tx owner revision intent] -> own ordinal handle before effects;
   finish-operation! [tx owner handle outcome] -> row, retains outer barrier;
   complete-operation! [tx owner handle private-json phase] -> safe outer row;
   checkpoint! [tx owner revision private-json phase] requires ALL receipts known;
   transfer! [tx owner child-table entity] -> child+parent reservation atomically.
   owner is {:table agent_run|cron :id id :fence fence}; table values are strings.
   allocation is {:limit 0..128 :generation-cap 1..262144}. No defaults/refunds.
   A failed/unknown commit must recover by the SAME host run ID, never retry or
   fall back. Stale fences/revisions and unresolved ancestors fail closed."
  (:require [yetibot.core.db.util :as db.util]
            [clojure.java.jdbc :as sql]
            [clojure.data.json :as json]
            [cuerdas.core :refer [kebab snake]]))

(def inherited-kind "app-inherited-v1")
(def execution-columns
  [[:execution-kind :text]
   [:execution-state :text]
   [:state-revision :integer "NOT NULL" "DEFAULT 0"]
   [:worker-fence :integer "NOT NULL" "DEFAULT 0"]
   [:execution-limit :integer]
   [:execution-used :integer "NOT NULL" "DEFAULT 0"]
   [:execution-reserved :integer "NOT NULL" "DEFAULT 0"]
   [:operation-ordinal :integer "NOT NULL" "DEFAULT 0"]
   [:generation-cap :integer]
   [:execution-phase :text]
   [:operation-receipts :text]])

(def schema
  {:schema/table "agent_run"
   :schema/specs (into [[:run-id :text "NOT NULL"]
                        [:request :text "NOT NULL"]
                        [:target :text]
                        [:context-channel :text]
                        [:status-id :text]
                        [:adapter-uuid :text]
                        [:mentions :text]
                        [:on-discord :boolean "NOT NULL" "DEFAULT false"]
                        [:attempts :integer "NOT NULL" "DEFAULT 1"]]
                       (concat execution-columns (db.util/default-fields)))})

(def create (partial db.util/create (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

;; These helpers own only the two existing durable allowance rows. The app
;; supplies/validates private checkpoint codecs and identity, never credentials.
;; Every mutator requires the caller's JDBC transaction: cron insertion and
;; parent reservation must not commit independently. Revisions are optimistic
;; state versions; fences invalidate ALL handles from an older worker. A child
;; receipt never releases the enclosing barrier; checkpoint! alone does so.
(defn- table-name [table]
  (when-not (#{"agent_run" "cron"} table)
    (throw (ex-info "Invalid inherited owner table." {})))
  (db.util/qualified-table-name table))

(defn require-transaction! [tx]
  (when-not (and (:connection tx)
                (not (.getAutoCommit ^java.sql.Connection (:connection tx))))
    (throw (ex-info "Inherited state requires a caller-owned transaction." {}))))

(defn verify-schema!
  "Verify required inherited columns despite permissive legacy ALTER handling.
   Called on every inherited create/claim; failure blocks only this branch."
  [tx table]
  (sql/query tx [(str "SELECT "
                     (clojure.string/join "," (map (comp snake first) execution-columns))
                     " FROM " (table-name table) " WHERE false")])
  true)

(defn read-row [db table id]
  (first (sql/query db [(str "SELECT * FROM " (table-name table) " WHERE id=?") id]
                   {:identifiers kebab})))
(defn lock-row! [tx table id]
  (require-transaction! tx)
  (or (first (sql/query tx [(str "SELECT * FROM " (table-name table)
                                " WHERE id=? FOR UPDATE") id]
                       {:identifiers kebab}))
      (throw (ex-info "Inherited owner is absent." {}))))
(defn- update-row! [tx table id attrs]
  (when-not (= [1] (sql/update! tx (table-name table) attrs ["id=?" id]
                              {:entities snake}))
    (throw (ex-info "Inherited state update was not saved." {}))))
(defn bounded-json
  "Validate core-owned checkpoint syntax and the two MiB UTF-8 bound. Null
   state remains valid; host-specific codec/identity checks belong to the host."
  [state]
  (when state
    (when-not (and (string? state)
                   (<= (alength (.getBytes ^String state "UTF-8")) 2097152))
      (throw (ex-info "Private checkpoint exceeds two MiB." {})))
    (json/read-str state)
    state))
(defn- validate-allocation! [{:keys [limit generation-cap]}]
  (when-not (and (integer? limit) (<= 0 limit 128)
                 (integer? generation-cap) (<= 1 generation-cap 262144))
    (throw (ex-info "Invalid inherited allocation." {}))))
(defn- valid-row! [row]
  (validate-allocation! {:limit (:execution-limit row)
                         :generation-cap (:generation-cap row)})
  (let [{:keys [execution-used execution-reserved execution-limit
                operation-ordinal]} row]
    (when-not (and (= inherited-kind (:execution-kind row))
                   (every? #(and (integer? %) (<= 0 % 128))
                           [execution-used execution-reserved operation-ordinal])
                   (= execution-used operation-ordinal)
                   (<= (+ execution-used execution-reserved) execution-limit))
      (throw (ex-info "Invalid inherited ledger." {}))))
  row)
(defn- receipts [row]
  (let [result (json/read-str (or (:operation-receipts row) "[]") :key-fn keyword)]
    (when-not (and (vector? result) (= (count result) (:execution-used row))
                   (every? true? (map-indexed
                     (fn [i r]
                       (and (= (inc i) (:ordinal r))
                            (#{"intent" "definitive" "unknown"} (:state r))
                            (or (nil? (:parent-ordinal r))
                                (and (integer? (:parent-ordinal r))
                                     (<= 1 (:parent-ordinal r) i))))) result)))
      (throw (ex-info "Invalid inherited operation receipts." {})))
    result))
(defn- owned-row! [tx {:keys [table id fence]}]
  (let [row (valid-row! (lock-row! tx table id))]
    (when-not (and (= fence (:worker-fence row))
                   (#{"ready" "queued" "operation-in-flight"} (:execution-phase row)))
      (throw (ex-info "Stale fence or terminal inherited owner." {})))
    row))
(defn- version! [row expected]
  (when-not (= expected (:state-revision row))
    (throw (ex-info "Stale inherited state revision." {}))))

(defn create-inherited!
  "[tx table entity {:limit n :generation-cap n}] -> committed-row candidate.
   Callers must commit before scheduling. No scheduling/retry/fallback here."
  [tx table entity allocation]
  (require-transaction! tx)
  (verify-schema! tx table)
  (validate-allocation! allocation)
  (bounded-json (:execution-state entity))
  (when (= table "agent_run")
    (when-not (string? (:run-id entity))
      (throw (ex-info "A host run identity is required." {})))
    (sql/query tx ["SELECT pg_advisory_xact_lock(hashtext(?))" (:run-id entity)])
    (when (seq (sql/query tx [(str "SELECT id FROM " (table-name table)
                                   " WHERE run_id=?") (:run-id entity)]))
      (throw (ex-info "Run identity already exists; recover that same run." {}))))
  (let [attrs (merge entity
                {:execution-kind inherited-kind :state-revision 0 :worker-fence 0
                 :execution-limit (:limit allocation) :execution-used 0
                 :execution-reserved 0 :operation-ordinal 0
                 :generation-cap (:generation-cap allocation)
                 :operation-receipts "[]" :execution-phase "queued"})
        inserted (first (sql/insert! tx (table-name table) attrs {:entities snake}))]
    (when-not (:id inserted) (throw (ex-info "Inherited insertion failed." {})))
    (read-row tx table (:id inserted))))

(defn claim! [tx table id expected-revision]
  (verify-schema! tx table)
  (let [row (valid-row! (lock-row! tx table id))
        rs (receipts row)]
    (version! row expected-revision)
    (if (or (= "operation-in-flight" (:execution-phase row))
            (some #(#{"intent" "unknown"} (:state %)) rs))
      (do (update-row! tx table id {:execution-phase "unknown"
                                    :worker-fence (inc (:worker-fence row))
                                    :state-revision (inc (:state-revision row))})
          {:status :unknown})
      (do
        (when-not (#{"queued" "ready"} (:execution-phase row))
          (throw (ex-info "Inherited owner cannot resume." {})))
        (update-row! tx table id {:worker-fence (inc (:worker-fence row))
                                 :execution-phase "ready"
                                 :state-revision (inc (:state-revision row))})
        (assoc (read-row tx table id)
               :owner {:table table :id id :fence (inc (:worker-fence row))}
               :status :claimed)))))

(defn admit!
  "[tx owner revision {:kind string :parent-ordinal optional}] -> own handle.
   Admission and intent are atomic before effects, including subordinate sends."
  [tx {:keys [table id] :as owner} expected intent]
  (let [row (owned-row! tx owner) rs (receipts row)
        parent (:parent-ordinal intent)
        ordinal (inc (:operation-ordinal row))]
    (version! row expected)
    (when-not (and (#{"command" "tool" "inference" "tick" "delivery"} (:kind intent))
                   (< (+ (:execution-used row) (:execution-reserved row))
                      (:execution-limit row))
                   (not-any? #(= "unknown" (:state %)) rs)
                   (if parent
                     (and (integer? parent) (<= 1 parent (count rs))
                          (= "intent" (:state (nth rs (dec parent)))))
                     (not-any? #(= "intent" (:state %)) rs)))
      (throw (ex-info "Inherited admission exhausted or barrier unavailable." {})))
    (let [receipt {:ordinal ordinal :parent-ordinal parent
                   :kind (:kind intent) :state "intent"}
          revision (inc (:state-revision row))]
      (update-row! tx table id
        {:execution-used (inc (:execution-used row)) :operation-ordinal ordinal
         :operation-receipts (json/write-str (conj rs receipt))
         :execution-phase "operation-in-flight" :state-revision revision})
      (assoc receipt :owner owner :revision revision))))

(defn finish-operation!
  "Finish only this handle. Definitive subordinate completion retains ancestor
   intent and phase. Any unknown is sticky and blocks all subsequent effects."
  [tx {:keys [table id] :as owner} handle outcome]
  (let [row (owned-row! tx owner) rs (receipts row)
        ordinal (:ordinal handle) receipt (get rs (dec (or ordinal 0)))]
    (when-not (and (= owner (:owner handle)) (= "intent" (:state receipt))
                   (#{:definitive :unknown} outcome))
      (throw (ex-info "Stale inherited operation receipt." {})))
    (let [outstanding (some #(and (= ordinal (:parent-ordinal %))
                                  (not= "definitive" (:state %))) rs)]
      (when (and (= :definitive outcome) outstanding)
        (throw (ex-info "An enclosing operation has unresolved children." {}))))
    (update-row! tx table id
      (cond-> {:operation-receipts (json/write-str
                                    (assoc-in rs [(dec ordinal) :state] (name outcome)))
               :state-revision (inc (:state-revision row))}
        (= :unknown outcome) (assoc :execution-phase "unknown")))
    (read-row tx table id)))

(defn checkpoint!
  "[tx owner revision private-json phase] commits an exact OUTER safe boundary.
   All admitted receipts must be definitive. Inner finish cannot call this on
   behalf of its ancestor. phase is ready or terminal; terminal clears state."
  [tx {:keys [table id] :as owner} expected state phase]
  (let [row (owned-row! tx owner)]
    (version! row expected)
    (when-not (and (#{"ready" "terminal"} phase)
                   (every? #(= "definitive" (:state %)) (receipts row)))
      (throw (ex-info "Checkpoint has unresolved enclosing operations." {})))
    (update-row! tx table id {:execution-state (when (= "ready" phase)
                                               (bounded-json state))
                             :execution-phase phase
                             :state-revision (inc (:state-revision row))})
    (read-row tx table id)))

(defn transfer!
  "Transfer ALL unused/unreserved units under the parent row lock in tx.
   owner includes fence for a claimed worker; unclaimed queued allocation may
   transfer before workers start. No refund; uncertain commit recovers same ID."
  [tx {:keys [table id fence] :as owner} child-table entity]
  (let [row (if fence (owned-row! tx owner)
                (valid-row! (lock-row! tx table id)))
        _ (when (or (= "unknown" (:execution-phase row))
                    (and (nil? fence) (not= "queued" (:execution-phase row))))
            (throw (ex-info "Unclaimed parent cannot transfer." {})))
        available (- (:execution-limit row) (:execution-used row)
                     (:execution-reserved row))
        child (create-inherited! tx child-table entity
                                 {:limit available :generation-cap (:generation-cap row)})]
    (update-row! tx table id {:execution-reserved (+ (:execution-reserved row) available)
                             :state-revision (inc (:state-revision row))})
    child))

(defn reserve-remaining!
  "[tx owner] -> allocation. Only for insertion/conversion in THIS transaction;
   caller must persist the destination before commit, never use outside tx."
  [tx {:keys [table id fence] :as owner}]
  (let [row (if fence (owned-row! tx owner)
                (valid-row! (lock-row! tx table id)))
        _ (when (and (nil? fence) (not= "queued" (:execution-phase row)))
            (throw (ex-info "Unclaimed parent cannot reserve." {})))
        available (- (:execution-limit row) (:execution-used row)
                     (:execution-reserved row))]
    (update-row! tx table id {:execution-reserved (+ (:execution-reserved row) available)
                             :state-revision (inc (:state-revision row))})
    {:limit available :generation-cap (:generation-cap row)}))

(defn allocation-attrs
  "Initial ledger attrs for native cron INSERT/UPDATE inside allocation tx."
  [allocation]
  (validate-allocation! allocation)
  {:execution-kind inherited-kind :state-revision 0 :worker-fence 0
   :execution-limit (:limit allocation) :execution-used 0 :execution-reserved 0
   :operation-ordinal 0 :generation-cap (:generation-cap allocation)
   :operation-receipts "[]" :execution-phase "queued"})

(defn complete-operation!
  "The outer executor commits its full definitive reply AND exact checkpoint
   in the same transaction. No subordinate may release an ancestor's barrier."
  [tx owner handle state phase]
  (let [row (finish-operation! tx owner handle :definitive)]
    (checkpoint! tx owner (:state-revision row) state phase)))
