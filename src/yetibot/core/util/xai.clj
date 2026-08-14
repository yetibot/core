(ns yetibot.core.util.xai
  "Shared utilities for interacting with the xAI API."
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.config :refer [get-config]]))

(s/def ::key string?)
(s/def ::config (s/keys :req-un [::key]))

(def config (or (:value (get-config ::config [:xai])) {}))

(defn configured? []
  (some? (:key config)))

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
