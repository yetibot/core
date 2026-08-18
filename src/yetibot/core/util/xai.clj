(ns yetibot.core.util.xai
  "Shared utilities for interacting with the xAI API."
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.config :refer [get-config]]))

(s/def ::key string?)
(s/def ::cost-per-image (s/or :string string? :number number?))
(s/def ::cost-per-prompt (s/or :string string? :number number?))
(s/def ::config (s/keys :req-un [::key] :opt-un [::cost-per-image ::cost-per-prompt]))

(def config (or (:value (get-config ::config [:xai])) {}))

(defn configured? []
  (some? (:key config)))

(defn- parse-number
  "Parse a string to a number, returning the number unchanged if it's already a number.
   Returns nil if parsing fails."
  [v]
  (if (string? v)
    (try
      (Double/parseDouble v)
      (catch Exception _ nil))
    v))

(def ^:private default-cost-per-image 0.04)
(def ^:private default-cost-per-prompt 0.01)

(defn cost-per-image []
  (or (parse-number (:cost-per-image config))
      default-cost-per-image))

(defn cost-per-prompt []
  (or (parse-number (:cost-per-prompt config))
      default-cost-per-prompt))

(defn generate-image
  "Call the xAI API to generate an image from a text prompt."
  [prompt]
  (let [api-key (:key config)
        url "https://api.x.ai/v1/images/generations"
        body {:model "grok-imagine-image-2.0"
              :prompt prompt
              :n 1
              :response_format "b64_json"}
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
                         :status status}))))))

(defn generate-text
  "Call the xAI API to generate text from a prompt using grok-4.6."
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
        text
        (throw (ex-info "No chat completion content returned from xAI API."
                        {:response-body (:body response)})))
      (let [error-msg (or (get-in (:body response) [:error :message])
                          (str "HTTP error " status))]
        (error "xai: API error" status "-" error-msg)
        (throw (ex-info (str "xAI API error: " error-msg)
                        {:type :xai-api-error
                         :status status}))))))
