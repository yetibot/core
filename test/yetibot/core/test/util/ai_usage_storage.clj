(ns yetibot.core.test.util.ai-usage-storage
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.core.db :as database]
            [yetibot.core.db.ai-usage :as store]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.core.test.support.ai-usage
             :refer [attrs with-store metrics]]))

(fact "coverage is durable and initialized once, not reset on each startup"
  (with-store
    (fn [rows _]
      (store/start!)
      (let [first-row (first (rows))]
        (store/start!)
        (count (rows)) => 1
        (:event_kind first-row) => "coverage"
        (:started_at (first (rows))) => (:started_at first-row)))))

(fact "durable pending precedes inference and only reviewed metrics persist"
  (with-store
    (fn [rows _]
      (let [answer {:text "PRIVATE-ANSWER"}
            result (usage/capture! attrs
                     (fn []
                       (count (rows)) => 1
                       (:state (first (rows))) => "pending"
                       (metrics (first (rows))) => [{:model "one"}]
                       answer)
                     (constantly {:input_tokens 12 :output_tokens 5
                                  :usd 0.03M :cost_kind "reported"
                                  :PRIVATE "PRIVATE-RESULT"}))
            row (first (rows))]
        result => answer
        (:state row) => "completed"
        (metrics row) => [{:model "one" :requests 1 :input_tokens 12
                          :output_tokens 5 :usd 0.03 :cost_kind "reported"}]
        (string/includes? (pr-str (rows)) "PRIVATE") => false
        ;; A later connection sees the persisted event, not an in-memory counter.
        (count (rows)) => 1
        (store/finish! (:event_id row)
                       {:state "completed" :model-usage [{:usd 999}]})
        (metrics (first (rows))) => (metrics row)))))

(fact "a final write failure preserves the answer and the honest pending row"
  (with-store
    (fn [rows _]
      (with-redefs [store/finish! (fn [& _] (throw (Exception. "PRIVATE")))]
        (usage/capture! attrs (constantly "answer")
                        (constantly {:usd 4 :cost_kind "reported"}))
        => "answer")
      (:state (first (rows))) => "pending"
      (metrics (first (rows))) => [{:model "one"}])))

(fact "malformed final metrics cannot discard a completed answer"
  (with-store
    (fn [rows _]
      (try (usage/capture! attrs (constantly "answer")
                          (constantly {:usd 1/3 :cost_kind "reported"}))
           (catch Exception _ :lost-answer)) => "answer"
      (:state (first (rows))) => "pending")))

(fact "an uncertain request is not retried and private exceptions are not stored"
  (with-store
    (fn [rows _]
      (let [error (ex-info "PRIVATE-TRANSPORT" {:body "PRIVATE-RESPONSE"})
            calls (atom 0)]
        (try (usage/capture! attrs #(do (swap! calls inc) (throw error))
                             (constantly {}))
             (catch Exception e (identical? error e))) => true
        @calls => 1
        (:state (first (rows))) => "unknown"
        (metrics (first (rows))) => [{:model "one"}]
        (string/includes? (pr-str (rows)) "PRIVATE") => false))))

(fact "nested calls are separate events without duplicating a parent total"
  (with-store
    (fn [rows _]
      (usage/capture! attrs
        #(usage/capture! (assoc attrs :provider "other" :operation "image")
                         (constantly :answer)
                         (constantly {:usd 0.01M :cost_kind "estimate"}))
        (constantly {:usd 0.02M :cost_kind "reported"}))
      (let [events (rows)
            root (first (filter #(nil? (:parent_id %)) events))
            child (first (filter :parent_id events))]
        (count events) => 2
        (:parent_id child) => (:event_id root)
        (:context_id child) => (:context_id root)
        (set (map #(get-in (metrics %) [0 :cost_kind]) events))
        => #{"reported" "estimate"}))))

(fact "the normal startup schema loader discovers the additive ledger"
  (boolean (some #(= "ai_usage" (:schema/table %)) (database/schemas))) => true)

(fact "concurrent real calls each commit one unique reservation and completion"
  (with-store
    (fn [rows _]
      (let [calls (atom 0)
            jobs (mapv (fn [_]
                         (future
                           (usage/capture! attrs
                             #(do (swap! calls inc) :answer)
                             (constantly {:input_tokens 1 :output_tokens 1}))))
                       (range 4))]
        (try
          (mapv #(deref % 10000 :timed-out) jobs)
          => [:answer :answer :answer :answer]
          @calls => 4
          (count (rows)) => 4
          (count (set (map :event_id (rows)))) => 4
          (set (map :state (rows))) => #{"completed"}
          (reduce + (map #(get-in (metrics %) [0 :requests]) (rows))) => 4
          (finally (doseq [job jobs] (future-cancel job))))))))

(fact "recognizable credentials from other providers cannot become model labels"
  (with-store
    (fn [rows _]
      (doseq [model ["xai-OTHER-CREDENTIAL-abcdefghijklmnop"
                     "AIza-FIXTURE-KEY-abcdefghijklmnopqrst"]]
        (usage/capture! (assoc attrs :model model :secrets ["different-key"])
          (constantly :answer) (constantly {})))
      (mapv #(get-in (metrics %) [0 :model]) (rows))
      => ["redacted" "redacted"])))

(fact "credential labels and invalid amounts cannot become ledger metadata"
  (with-store
    (fn [rows _]
      (usage/capture! (assoc attrs :model "PRIVATE-KEY") (constantly :answer)
        (constantly {:usd ##NaN :cost_kind "reported"
                     :input_tokens -1 :output_tokens 0 :cached_tokens "2"}))
      (metrics (first (rows)))
      => [{:model "redacted" :requests 1 :output_tokens 0}]
      (string/includes? (pr-str (rows)) "PRIVATE") => false)))
