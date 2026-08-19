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

(def cost-per-image 0.04)

(declare generate-text)

(defn optimize-image-prompt
  "Use Grok text generation to rewrite/optimize the prompt to make subjects,
   direction of actions, and spatial relationships extremely explicit."
  [prompt]
  (if (clojure.string/blank? prompt)
    prompt
    (try
      (let [system-prompt "You are an expert image prompt optimizer. The user wants to generate an image from the prompt. To ensure that the image generator does NOT reverse the subjects or people (e.g., if the user says 'A slapping B', it shouldn't show B slapping A), rewrite the prompt into a highly detailed, visually unambiguous description of the scene. Clearly define who is the active subject (initiating the action) and who is the passive object (receiving the action). Describe their physical actions, relative positions (e.g. 'on the left, person A is doing X; on the right, person B is reacting to X'), poses, facial expressions, and composition in explicit detail. Do NOT use ambiguous phrasing. Ensure the direction of the action is 100% clear. Keep the final description concise but extremely descriptive and visually specific for an image generator (like Grok Imagine). Do NOT include any meta-text, conversational preamble, or explanations; return ONLY the optimized image prompt."
            full-prompt (str system-prompt "\n\nUser prompt: \"" prompt "\"")
            {:keys [text]} (generate-text full-prompt)]
        (if (and text (not (clojure.string/blank? text)))
          (clojure.string/trim text)
          prompt))
      (catch Exception e
        (error "xai: failed to optimize prompt:" (.getMessage e))
        prompt))))

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
         optimized-prompt (if has-images?
                            prompt
                            (optimize-image-prompt prompt))
         final-prompt (cond
                        (not has-images?) optimized-prompt
                        (clojure.string/blank? prompt) (if (> (count image-urls) 1)
                                                         "combine these images beautifully"
                                                         "remix this image")
                        (and (> (count image-urls) 1)
                             (not (clojure.string/includes? (clojure.string/lower-case prompt) "combine"))
                             (not (clojure.string/includes? (clojure.string/lower-case prompt) "merge")))
                        (str "Combine these images: " prompt)
                        :else prompt)
         body (let [b {:model "grok-imagine-image-2.0"
                       :prompt final-prompt
                       :n 1
                       :quality "low"
                       :response_format "b64_json"}]
                (if has-images?
                  (if (> (count image-urls) 1)
                    (assoc b :images (mapv (fn [u] {:url u :type "image_url"}) image-urls))
                    (assoc b :image {:url (first image-urls)
                                     :type "image_url"}))
                  b))
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
