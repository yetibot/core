(ns yetibot.core.util.ai-usage
  "Minimal durable AI accounting. Never retains content or credentials."
  (:require [clojure.string :as string]
            [taoensso.timbre :as log]
            [yetibot.core.db.ai-usage :as store]
            [yetibot.core.util.agent-execution :as execution])
  (:import [java.util UUID]))

(defn counter? [x]
  (and (integer? x) (<= 0 x 1000000000000)))

(defn amount? [x]
  (and (number? x) (Double/isFinite (double x)) (<= 0 x 1000000000)))

(defn tokens
  "Allowlisted native usage counters; absent or invalid values stay absent."
  [body]
  (let [u (or (:usage body) (get-in body [:result :usage])
              (:usageMetadata body))
        values {:input_tokens (or (:input_tokens u) (:prompt_tokens u)
                                  (:promptTokenCount u))
                :output_tokens (or (:output_tokens u) (:completion_tokens u)
                                   (:candidatesTokenCount u))
                :cached_tokens (or (get-in u [:input_tokens_details :cached_tokens])
                                   (get-in u [:prompt_tokens_details :cached_tokens])
                                   (:cachedContentTokenCount u))
                :reasoning_tokens
                (or (get-in u [:output_tokens_details :reasoning_tokens])
                    (get-in u [:completion_tokens_details :reasoning_tokens])
                    (:thoughtsTokenCount u))
                :neurons (:neurons u)}]
    (into {} (filter (fn [[key value]]
                       ((if (= key :neurons) amount? counter?) value))) values)))

(defonce ^:private required? (atom false))

(defn require-tracking!
  "Make reservations mandatory before opening a running bot's entry points."
  []
  (reset! required? true))

(defn start! []
  (require-tracking!)
  (try (store/start!)
       (catch Exception _
         (throw (ex-info "AI usage tracking could not start."
                         {:ai/error true :usage/unavailable true})))))

(def grok-rates
  "Official Grok4.7 USD/M, checked 2026-11-01; short/200k+ context tiers."
  {:short [2M 0.5M 6M] :long [4M 1M 12M]})

(defn grok-token-cost [input output cached]
  (when (and (every? counter? [input output cached]) (<= cached input))
    (let [[in cache out] (grok-rates (if (>= input 200000) :long :short))]
      (/ (+ (* (- input cached) in) (* cached cache) (* output out)) 1000000M))))

(defn response-metrics
  "HTTP receipt plus native counters. Error/unknown cost is never zero."
  [response]
  (let [status (:status response)]
    (merge (tokens (:body response))
           {:http_status status
            :state (if (and (integer? status) (<= 200 status 299))
                     "completed" "failed")})))

(defn xai-metrics
  "Only for hardcoded api.x.ai transports; arbitrary hosts cannot claim USD."
  [model response]
  (let [body (:body response) u (:usage body)
        ticks (:cost_in_usd_ticks u)
        known-cost (when (and (integer? ticks) (<= 0 ticks 1000000000000000000))
                     (/ (bigdec ticks) 10000000000M))
        counters (tokens body)
        estimate (when (and (= model "grok-4.7")
                            (not (contains? u :cost_in_usd_ticks)))
                   (grok-token-cost (:input_tokens counters)
                                    (:output_tokens counters)
                                    (get counters :cached_tokens 0)))]
    (cond-> (response-metrics response)
      (some? (or known-cost estimate))
      (assoc :usd (or known-cost estimate)
             :cost_kind (if known-cost "reported" "estimate")))))

(defn cli-metrics
  "Gemini CLI-native per-model summaries, not individual HTTP traces or bills."
  [result]
  (let [models (get-in result [:stats :models])]
    (cond-> {:state (cond (:timed-out result) "unknown"
                         (= 0 (:exit result)) "completed"
                         :else "failed")}
      (and (map? models) (<= 1 (count models) 32))
      (assoc :models
        (mapv (fn [[model data]]
                (let [t (:tokens data)
                      input (:input t) cached (:cached t)]
                  {:model (if (keyword? model) (subs (str model) 1) model)
                   :requests (get-in data [:api :totalRequests])
                   :input_tokens (or (:prompt t)
                                     (when (every? counter? [input cached])
                                       (+ input cached)))
                   :uncached_tokens input :cached_tokens cached
                   :output_tokens (:candidates t)
                   :reasoning_tokens (:thoughts t)})) models)))))

(def ^:dynamic *context-id* nil)
(def ^:dynamic *request-id* nil)

(defn context-id [] (or *context-id* (str (UUID/randomUUID))))

(def ^:private credential-label-pattern
  (re-pattern (str "(?i)(?:sk[-_]|github_pat_|ghp_|cfut_|bearer|"
                   "xai-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{20,})")))

(defn- label [value secrets]
  (if (and (string? value)
           (re-matches #"[A-Za-z0-9@._:/-]{1,200}" value)
           (not (string/includes? value "://"))
           (not (re-find credential-label-pattern value))
           (not-any? #(and (string? %) (seq %) (string/includes? value %))
                     secrets))
    value "redacted"))

(defn- model-usage [attrs result default-requests]
  (let [models (:models result)
        models (if (and (sequential? models) (<= 1 (count models) 32))
                 models [(assoc result :model (:model attrs))])]
    (mapv
      (fn [row]
        (let [usd (:usd row) cost-kind (:cost_kind row)]
          (cond->
            (into {:model (label (:model row) (:secrets attrs))}
                  (filter (fn [[key value]]
                            ((if (= :neurons key) amount? counter?) value)))
                  (assoc (select-keys row
                           [:input_tokens :output_tokens :uncached_tokens
                            :cached_tokens
                            :reasoning_tokens :neurons :images :seconds])
                         :requests (get row :requests default-requests)))
            (and (amount? usd) (#{"reported" "estimate"} cost-kind))
            (assoc :usd (bigdec usd) :cost_kind cost-kind))))
      models)))

(defn- finish! [id prepare]
  (try (store/finish! id (prepare))
       (catch Exception _
         (log/warn "AI usage finalization unavailable; pending record retained"
                   {:event-id id}))))

(defn- running-database? []
  (when-let [state (some-> (find-ns 'yetibot.core.db)
                           (ns-resolve 'connected?) var-get)]
    (true? @state)))

(defn- accounted-capture!
  "Save pending before wire IO. Never repeat inference or lose its answer
   because final accounting failed. A standalone utility outside a started
   Yetibot database retains its API; running bots establish tracking at startup."
  [attrs request project]
  (if-not (or @required? (running-database?))
    (request)
    (let [id (str (UUID/randomUUID))
          session? (= "agent" (:operation attrs))
          initial {:event-id id :context-id (context-id) :parent-id *request-id*
                   :event-kind (if session? "session" "request")
                   :provider (label (:provider attrs) (:secrets attrs))
                   :operation (if (#{"text" "image" "video" "classification"
                                     "agent"} (:operation attrs))
                                (:operation attrs) "other")
                   :model-usage (model-usage attrs {} nil)}]
      (try
        (when-not (= [1] (store/begin! initial))
          (throw (ex-info "Usage reservation was not saved." {})))
        (catch Exception _
          (throw (ex-info
                   "AI usage tracking is unavailable. No AI request was sent."
                   {:ai/error true :usage/unavailable true}))))
      (binding [*context-id* (:context-id initial) *request-id* id]
        (try
          (let [response (request)]
            (finish! id
              (fn []
                (let [result (project response) status (:http_status result)]
                  {:state (if (#{"completed" "failed" "unknown"} (:state result))
                            (:state result) "completed")
                   :http-status (when (and (integer? status) (<= 100 status 599))
                                  status)
                   :model-usage (model-usage attrs result (when-not session? 1))})))
            response)
          (catch Throwable e
            (let [data (ex-data e)
                  status (or (:status data) (get-in data [:ai/diagnostic :status]))
                  status (when (and (integer? status) (<= 100 status 599)) status)]
              (finish! id
                (fn []
                  {:state (if (and status (not (<= 200 status 299)))
                            "failed" "unknown")
                   :http-status status
                   :model-usage (model-usage attrs {} (when status 1))})))
            (throw e)))))))


(def ^:dynamic *admit-inference*
  "Optional host [actual-transport-attrs] -> own admission handle, before IO.
   Active even without monthly tracking. Denial must throw, never retry." nil)
(def ^:dynamic *finish-inference*
  "Optional host [handle {:state :definitive|:unknown ...}] -> receipt only.
   Must not release any enclosing tool/command barrier or save its checkpoint."
  nil)

(defn capture! [attrs request project]
  (let [origin (or execution/*origin* execution/*context*)
        _ (when (and origin (= "agent" (:operation attrs)))
            (throw (ex-info "CLI sessions cannot enforce inherited admissions." {})))
        admit (or *admit-inference*
                  (when origin #(execution/invoke! origin :admit-inference %)))
        finish (or *finish-inference*
                   (when origin #(execution/invoke! origin :finish-operation %1 %2)))
        handle (when admit (admit attrs))
        sent? (atom false)
        response (try
                   (binding [execution/*operation* (or handle execution/*operation*)]
                     (accounted-capture! attrs
                       #(do (reset! sent? true) (request)) project))
                   (catch Throwable e
                     (when finish
                       (finish handle {:state (if @sent? :unknown :definitive)
                                       :sent? @sent? :failed? true}))
                     (throw e)))]
    (when finish (finish handle {:state :definitive :sent? true}))
    response))
