(ns yetibot.core.util.public-result)

(def ^:private reviewed-marker (Object.))

(defn reviewed
  "Mark an explicitly reviewed public projection authored by trusted command
  code. Select public fields before calling this; never mark arbitrary maps.
  The in-process marker cannot be supplied by serialized provider/user data."
  [result]
  (with-meta result (assoc (meta result) ::reviewed reviewed-marker)))

(defn- approved? [result]
  (and (map? result)
       (identical? reviewed-marker (::reviewed (meta result)))))

(defn projection
  "Carry only authenticated public fields, never the raw command result."
  [result]
  (when (approved? result)
    (let [fields (select-keys result [:result/public-data :result/public-text])]
      (when (seq fields) (reviewed fields)))))

(defn collection-data
  "Reviewed metadata in the same order/cardinality as the incoming rows.
  Unreviewed or unaligned input never gains approval from a collection op."
  [{:keys [public-result opts]}]
  (when (approved? public-result)
    (let [data (:result/public-data public-result)]
      (when (and (sequential? data) (sequential? opts)
                 (= (count data) (count opts)))
        data))))

(defn transform-collection
  "Apply a trusted row selection/ordering to the reviewed companion data.
  Callers choose indices once; stochastic operations must not run twice."
  [args result f]
  (if-let [data (collection-data args)]
    (reviewed (assoc result :result/public-data (f data)))
    result))

(defn item-projection [args index]
  (when-let [data (collection-data args)]
    (reviewed {:result/public-data (nth data index)})))

(defn forward
  "Keep reviewed metadata through an explicitly data-preserving operation."
  [{:keys [public-result data data-collection]} result]
  (if-let [p (projection public-result)]
    (reviewed
      (merge (if (map? result) result
               {:result/value result :result/data data
                :result/data-collection data-collection}) p))
    result))

(defn aggregate
  "Combine only reviewed child projections in output order. Unreviewed child
  positions stay nil; their raw results are never used as public fallbacks."
  [result children]
  (let [projections (mapv projection children)]
    (if (some #(contains? % :result/public-data) projections)
      (reviewed (assoc result :result/public-data
                       (mapv :result/public-data projections)))
      result)))

(def ^:dynamic *capture*
  "Request-local observer of approved public fields and existence flags only.
  Never receives the raw result, arguments, prompts or upstream exceptions."
  nil)

(defn observe!
  ([result] (observe! result true))
  ([result final?]
   (when (and *capture* (map? result))
     (try
       (let [approved? (approved? result)
             data? (and final? approved? (contains? result :result/public-data))]
         (*capture*
           {:data? data?
            :error? (or (boolean (:result/error result))
                        (true? (get-in result [:result/data :isError])))
            :public-data? data?
            :public-data (when data? (:result/public-data result))
            :public-text (when (and final? approved?) (:result/public-text result))
            :public-error (when approved? (:result/public-error result))}))
       (catch Exception _ nil)))
   result))

(defn error!
  ([kind] (error! kind nil))
  ([kind status]
   (when *capture*
     (try
       (*capture*
         {:error? true
          :public-error (cond-> {:stage "evaluation" :kind kind}
                          (and (integer? status) (<= 100 status 599))
                          (assoc :http-status status))})
       (catch Exception _ nil)))))
