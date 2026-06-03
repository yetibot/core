(ns yetibot.core.commands.veo
  (:require [clojure.string :as string]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.chat :as chat]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(defn redact
  "Strip a leaked Gemini API key from an error message before it reaches chat."
  [msg]
  (some-> msg (string/replace #"key=[^\s&\"]+" "key=***")))

(def model-presets
  {"lite" {:model "veo-3.1-lite-generate-preview" :duration 4}
   "fast" {:model "veo-3.1-fast-generate-preview" :duration 4}
   "gigaveo" {:model "veo-3.1-generate-preview" :duration 8}
   "preview" {:model "veo-3.1-generate-preview" :duration 8}
   "better" {:model "veo-3.1-generate-preview" :duration 8}})

(defn parse-model-and-prompt
  [raw-prompt]
  (let [words (string/split (string/trim raw-prompt) #"\s+" 2)
        first-word (string/lower-case (or (first words) ""))
        preset (get model-presets first-word)]
    (if (and preset (> (count words) 1))
      (assoc preset :prompt (second words))
      {:prompt raw-prompt})))

(defn veo-cmd
  "veo <prompt> # generate a short AI video with Veo
   gigaveo <prompt> # generate a high-quality 8-second video with flagship Veo 3.1 model

   Examples:
   veo a cat jumping
   veo lite a cat jumping
   veo gigaveo a cat jumping
   gigaveo a cat jumping"
  {:yb/cat #{:img :gif}}
  [{:keys [match chat-source user cmd]}]
  (if (gemini/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)
            preset (if (= cmd "gigaveo")
                     (assoc (get model-presets "gigaveo") :prompt prompt)
                     (let [parsed (parse-model-and-prompt prompt)]
                       (when (:model parsed)
                         parsed)))
            final-prompt (if preset (:prompt preset) prompt)
            model (if preset (:model preset) (gemini/veo-model))
            duration (if preset (:duration preset) (gemini/veo-duration))
            adapter chat/*adapter*
            target chat/*target*
            thread-ts chat/*thread-ts*
            user-mention (when-let [user-id (:id user)] (str "<@" user-id ">"))]
        (info "veo: starting async video generation for:" final-prompt
              "model:" model "duration:" duration "with" (count image-urls) "input image(s)")
        (future
          (binding [chat/*adapter* adapter
                    chat/*target* target
                    chat/*thread-ts* thread-ts]
            (try
              (let [video (gemini/generate-video final-prompt image-urls model duration)
                    id (store-image! video)
                    url (format "%s/generated-images/%s.mp4" (gemini/yetibot-base-url) id)
                    msg (if user-mention (str user-mention ": " url) url)]
                (info "veo: video generated, serving at" url)
                (chat/send-msg msg))
              (catch Exception e
                (error "veo: generation error in future:" (.getMessage e))
                (let [err-msg (str "Video generation failed: " (redact (.getMessage e)))
                      msg (if user-mention (str user-mention ": " err-msg) err-msg)]
                  (chat/send-msg msg))))))
        {:result/value (str "🎥 Grug start generating video for \"" final-prompt "\". This take some time (30s to 3m)...")})
      (catch Exception e
        (error "veo: initialization error:" (.getMessage e))
        {:result/error (str "Video generation initialization failed: " (redact (.getMessage e)))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.key` in config."}))

(cmd-hook #"veo"
  #".+" veo-cmd)

(cmd-hook #"gigaveo"
  #".+" veo-cmd)
