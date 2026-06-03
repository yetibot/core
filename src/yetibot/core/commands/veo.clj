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

(def ^:private video-url-pattern
  #"(https?://\S+\.mp4(?:[?\#]\S*)?)")

(defn- video-attachment? [{:keys [content-type content_type filename]}]
  (let [ct (or content-type content_type "")]
    (or (string/starts-with? ct "video/")
        (when filename
          (re-find #"(?i)\.(mp4)$" filename)))))

(defn- extract-video-attachment-urls [raw-event]
  (->> (:attachments raw-event)
       (filter video-attachment?)
       (mapv :url)))

(defn- extract-video-inline-urls [prompt]
  (let [urls (mapv first (re-seq video-url-pattern prompt))
        cleaned (reduce #(string/replace %1 %2 "") prompt urls)]
    {:urls urls :prompt (string/trim cleaned)}))

(defn extract-videos [prompt chat-source]
  (let [raw-event (:raw-event chat-source)
        attachment-urls (extract-video-attachment-urls raw-event)
        {inline-urls :urls p :prompt} (extract-video-inline-urls prompt)]
    {:prompt p
     :video-urls (into [] cat [attachment-urls inline-urls])}))

(defn- remove-video-attachments [chat-source]
  (update-in chat-source [:raw-event :attachments]
             (fn [attachments]
               (remove video-attachment? attachments))))

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
      (let [{:keys [prompt video-urls]} (extract-videos match chat-source)
            is-extension? (seq video-urls)
            cleaned-chat-source (if is-extension? (remove-video-attachments chat-source) chat-source)
            {:keys [prompt image-urls]} (image-input/extract-images prompt cleaned-chat-source)
            preset (if (= cmd "gigaveo")
                     (assoc (get model-presets "gigaveo") :prompt prompt)
                     (let [parsed (parse-model-and-prompt prompt)]
                       (when (:model parsed)
                         parsed)))
            final-prompt (if preset (:prompt preset) prompt)
            model (if preset (:model preset) (gemini/veo-model))
            model (if (and is-extension? (string/includes? model "lite"))
                    "veo-3.1-generate-preview"
                    model)
            duration (if is-extension? 8 (if preset (:duration preset) (gemini/veo-duration)))
            adapter chat/*adapter*
            target chat/*target*
            thread-ts chat/*thread-ts*
            user-mention (when-let [user-id (:id user)] (str "<@" user-id ">"))]
        (info "veo: starting async video generation for:" final-prompt
              "model:" model "duration:" duration
              "with" (count image-urls) "input image(s)"
              "and" (count video-urls) "input video(s)")
        (future
          (binding [chat/*adapter* adapter
                    chat/*target* target
                    chat/*thread-ts* thread-ts]
            (try
              (let [video (if (seq video-urls)
                            (gemini/generate-video final-prompt image-urls model duration (first video-urls))
                            (gemini/generate-video final-prompt image-urls model duration))
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
        (let [start-text (str "🎥 Grug start generating video for \"" final-prompt "\". This take some time (30s to 3m)...")
              start-text (if is-extension?
                           (str "💸 🔥  time to burn some money!\n" start-text)
                           start-text)]
          {:result/value start-text}))
      (catch Exception e
        (error "veo: initialization error:" (.getMessage e))
        {:result/error (str "Video generation initialization failed: " (redact (.getMessage e)))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.key` in config."}))

(cmd-hook #"veo"
  #".+" veo-cmd)

(cmd-hook #"gigaveo"
  #".+" veo-cmd)
