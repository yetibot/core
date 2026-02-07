(ns yetibot.core.commands.banana
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.config :refer [get-config]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(s/def ::key string?)

(s/def ::config (s/keys :req-un [::key]))

(def config (:value (get-config ::config [:gemini :api])))

(def default-model "gemini-2.0-flash-exp")

(defn gemini-model []
  (or (:model config) default-model))

(defn- extract-image
  "Extract the first image part from the Gemini API response."
  [response-body]
  (let [parts (get-in response-body [:candidates 0 :content :parts])]
    (some (fn [part]
            (when-let [inline-data (:inlineData part)]
              {:data (:data inline-data)
               :mime-type (:mimeType inline-data)}))
          parts)))

(defn- generate-image
  "Call the Gemini API to generate an image from a text prompt."
  [prompt]
  (let [api-key (:key config)
        model (gemini-model)
        url (format
             "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"
             model api-key)
        body {:contents [{:parts [{:text (str "Generate an image: " prompt)}]}]
              :generationConfig {:responseModalities ["TEXT" "IMAGE"]}}
        response (client/post url
                              {:content-type :json
                               :body (json/write-str body)
                               :as :json
                               :throw-exceptions true})]
    (extract-image (:body response))))

(defn- yetibot-base-url []
  (or (:value (get-config string? [:url]))
      (:value (get-config string? [:endpoint]))
      "http://localhost:3003"))

(defn banana-cmd
  "banana <prompt> # generate an image using Gemini nano banana image generation"
  {:yb/cat #{:img}}
  [{:keys [match]}]
  (if config
    (try
      (info "banana: generating image for prompt:" match)
      (if-let [image (generate-image match)]
        (let [id (store-image! image)
              base-url (yetibot-base-url)
              image-url (format "%s/generated-images/%s.png" base-url id)]
          (info "banana: image generated successfully, serving at" image-url)
          {:result/value image-url
           :result/data {:id id :prompt match :url image-url}})
        {:result/error "No image was generated. Try a different prompt."})
      (catch Exception e
        (error "banana: Gemini image generation error:" (.getMessage e))
        {:result/error (str "Image generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.api.key` in config."}))

(cmd-hook #"banana"
  #".+" banana-cmd)
