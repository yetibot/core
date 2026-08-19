(ns yetibot.core.util.xai
  "Shared utilities for interacting with the xAI API."
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.config :refer [get-config]]))

(s/def ::key string?)
(s/def ::config (s/keys :req-un [::key]))

(def config (or (:value (get-config ::config [:xai])) {}))

(defn configured? []
  (some? (:key config)))

(def cost-per-image 0.04)

(defn generate-image
  "Call the xAI API to generate an image from a text prompt.
   If image-urls is provided and non-empty, performs image editing via the /v1/images/edits endpoint."
  ([prompt] (generate-image prompt nil))
  ([prompt image-urls]
   (let [api-key (:key config)
         has-images? (seq image-urls)
         url (if has-images?
               "https://api.x.ai/v1/images/edits"
               "https://api.x.ai/v1/images/generations")
         final-prompt (if (and has-images? (clojure.string/blank? prompt))
                        "remix this image"
                        prompt)
         body (cond-> {:model "grok-imagine-image-2.0"
                       :prompt final-prompt
                       :n 1
                       :quality "low"
                       :response_format "b64_json"}
                has-images?
                (assoc :image {:url (first image-urls)
                               :type "image_url"}))
         response (client/post url
                               {:headers {"Authorization" (str "Bearer " api-key)}
                                :content-type :json
                                :body (json/write-str body)
                                :as :json
                                :throw-exceptions false})
         status (:status response)]
     (if (<= 200 status 299)
       (if-let [b64-data (get-in (:body response) [:data 0 :b64_json])]
         {:data b64-data
          :mime-type "image/jpeg"}
         (throw (ex-info "No image data returned from xAI API."
                         {:response-body (:body response)})))
       (let [error-msg (or (get-in (:body response) [:error :message])
                           (str "HTTP error " status))]
         (error "xai: API error" status "-" error-msg)
         (throw (ex-info (str "xAI API error: " error-msg)
                         {:type :xai-api-error
                          :status status})))))))

(defn generate-text
  "Call the xAI API to generate text from a prompt using grok-4.6.
   Returns a map with :text and :cost (in USD)."
  [prompt]
  (let [api-key (:key config)
        url "https://api.x.ai/v1/chat/completions"
        body {:model "grok-4.6"
              :messages [{:role "user" :content prompt}]}
        response (client/post url
                              {:headers {"Authorization" (str "Bearer " api-key)}
                               :content-type :json
                               :body (json/write-str body)
                               :as :json
                               :throw-exceptions false})
        status (:status response)]
    (if (<= 200 status 299)
      (if-let [text (get-in (:body response) [:choices 0 :message :content])]
        (let [usage (get-in (:body response) [:usage])
              prompt-tokens (get usage :prompt_tokens 0)
              completion-tokens (get usage :completion_tokens 0)
              raw-cost (+ (* prompt-tokens 0.000002)
                          (* completion-tokens 0.000006))
              cost (/ (Math/round (* raw-cost 1000000.0)) 1000000.0)]
          {:text text :cost cost})
        (throw (ex-info "No chat completion content returned from xAI API."
                        {:response-body (:body response)})))
      (let [error-msg (or (get-in (:body response) [:error :message])
                          (str "HTTP error " status))]
        (error "xai: API error" status "-" error-msg)
        (throw (ex-info (str "xAI API error: " error-msg)
                        {:type :xai-api-error
                         :status status}))))))

(defn generate-text-stream
  "Call the xAI API to generate text from a prompt using grok-4.6 with streaming.
   Calls `on-chunk` function with a map of {:type :thinking/:content, :text string?, :done boolean?}
   on every line/chunk."
  [prompt on-chunk]
  (let [api-key (:key config)
        url "https://api.x.ai/v1/chat/completions"
        body {:model "grok-4.6"
              :messages [{:role "user" :content prompt}]
              :stream true
              :stream_options {:include_usage true}}
        response (client/post url
                              {:headers {"Authorization" (str "Bearer " api-key)}
                               :content-type :json
                               :body (json/write-str body)
                               :as :reader
                               :throw-exceptions false})]
    (if (<= 200 (:status response) 299)
      (with-open [reader (:body response)]
        (doseq [line (line-seq reader)]
          (let [trimmed (str/trim line)]
            (when (and (not (empty? trimmed)) (str/starts-with? trimmed "data: "))
              (let [data-str (subs trimmed 6)]
                (if (= data-str "[DONE]")
                  (on-chunk {:done true})
                  (try
                    (let [chunk-json (json/read-str data-str :key-fn keyword)
                          delta (get-in chunk-json [:choices 0 :delta])
                          reasoning (:reasoning_content delta)
                          content (:content delta)
                          usage (:usage chunk-json)]
                      (cond
                        reasoning (on-chunk {:type :thinking :text reasoning})
                        content (on-chunk {:type :content :text content})
                        usage (on-chunk {:type :usage :usage usage})
                        :else nil))
                    (catch Exception e
                      (info "Failed to parse JSON stream chunk" e)))))))))
      (let [error-msg (str "HTTP error " (:status response))]
        (error "xai: API error" (:status response) "-" error-msg)
        (throw (ex-info (str "xAI API error: " error-msg)
                        {:type :xai-api-error
                         :status (:status response)}))))))
