(ns yetibot.core.util.public-result)

(def ^:private reviewed-marker (Object.))

(defn reviewed
  "Mark an explicitly reviewed public projection authored by trusted command
  code. Select public fields before calling this; never mark arbitrary maps.
  The in-process marker cannot be supplied by serialized provider/user data."
  [result]
  (with-meta result (assoc (meta result) ::reviewed reviewed-marker)))

(def ^:dynamic *capture*
  "Request-local observer of approved public fields and existence flags only.
  Never receives the raw result, arguments, prompts or upstream exceptions."
  nil)

(defn observe! [result]
  (when (and *capture* (map? result))
    (try
      (let [approved? (identical? reviewed-marker (::reviewed (meta result)))
            data? (and approved? (contains? result :result/public-data))]
        (*capture*
          {:data? data?
           :error? (or (boolean (:result/error result))
                       (true? (get-in result [:result/data :isError])))
           :public-data? data?
           :public-data (when data? (:result/public-data result))
           :public-error (when approved? (:result/public-error result))}))
      (catch Exception _ nil)))
  result)

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
