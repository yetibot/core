(ns yetibot.core.commands.veo
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.chat :as chat]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(defn veo-cmd
  "veo <prompt> # generate a short AI video with Veo

   Examples:
   veo a cat jumping
   veo a robot dancing in times square
   veo make @someone breakdance"
  {:yb/cat #{:img :gif}}
  [{:keys [match chat-source user]}]
  (if (gemini/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)
            adapter chat/*adapter*
            target chat/*target*
            thread-ts chat/*thread-ts*
            user-mention (when-let [user-id (:id user)] (str "<@" user-id ">"))]
        (info "veo: starting async video generation for:" prompt "with" (count image-urls) "input image(s)")
        (future
          (binding [chat/*adapter* adapter
                    chat/*target* target
                    chat/*thread-ts* thread-ts]
            (try
              (let [video (gemini/generate-video prompt image-urls)
                    id (store-image! video)
                    url (format "%s/generated-images/%s.mp4" (gemini/yetibot-base-url) id)
                    msg (if user-mention (str user-mention ": " url) url)]
                (info "veo: video generated, serving at" url)
                (chat/send-msg msg))
              (catch Exception e
                (error "veo: generation error in future:" (.getMessage e))
                (let [err-msg (str "Video generation failed: " (.getMessage e))
                      msg (if user-mention (str user-mention ": " err-msg) err-msg)]
                  (chat/send-msg msg))))))
        {:result/value (str "🎥 Grug start generating video for \"" prompt "\". This take some time (30s to 3m)...")})
      (catch Exception e
        (error "veo: initialization error:" (.getMessage e))
        {:result/error (str "Video generation initialization failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.key` in config."}))

(cmd-hook #"veo"
  #".+" veo-cmd)
