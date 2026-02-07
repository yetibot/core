(ns yetibot.core.util.gemini
  "Shared utilities for interacting with the Google Gemini API."
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.config :refer [get-config]]))

(s/def ::key string?)

(s/def ::config (s/keys :req-un [::key]))

(def config (:value (get-config ::config [:gemini :api])))

(def default-model "gemini-2.0-flash-exp")

(defn gemini-model []
  (or (:model config) default-model))

(defn configured? []
  (some? config))

(defn- extract-image
  "Extract the first image part from the Gemini API response."
  [response-body]
  (let [parts (get-in response-body [:candidates 0 :content :parts])]
    (some (fn [part]
            (when-let [inline-data (:inlineData part)]
              {:data (:data inline-data)
               :mime-type (:mimeType inline-data)}))
          parts)))

(defn generate-image
  "Call the Gemini API to generate an image from a text prompt.
   Accepts an optional system-instruction string for guiding generation style."
  ([prompt] (generate-image prompt nil))
  ([prompt system-instruction]
   (let [api-key (:key config)
         model (gemini-model)
         url (format
              "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"
              model api-key)
         body (cond-> {:contents [{:parts [{:text prompt}]}]
                       :generationConfig {:responseModalities ["TEXT" "IMAGE"]}}
                system-instruction
                (assoc :systemInstruction
                       {:parts [{:text system-instruction}]}))
         response (client/post url
                               {:content-type :json
                                :body (json/write-str body)
                                :as :json
                                :throw-exceptions true})]
     (extract-image (:body response)))))

(defn yetibot-base-url []
  (or (:value (get-config string? [:url]))
      (:value (get-config string? [:endpoint]))
      "http://localhost:3003"))
