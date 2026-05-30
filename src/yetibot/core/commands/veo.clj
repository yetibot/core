(ns yetibot.core.commands.veo
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(defn veo-cmd
  "veo <prompt> # generate a short AI video with Veo

   Examples:
   veo a cat jumping
   veo a robot dancing in times square
   veo make @someone breakdance"
  {:yb/cat #{:img :gif}}
  [{:keys [match chat-source]}]
  (if (gemini/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)]
        (info "veo: generating video for:" prompt "with" (count image-urls) "input image(s)")
        (let [video (gemini/generate-video prompt image-urls)
              id (store-image! video)
              url (format "%s/generated-images/%s.mp4" (gemini/yetibot-base-url) id)]
          (info "veo: video generated, serving at" url)
          {:result/value url
           :result/data {:id id :prompt match :url url}}))
      (catch Exception e
        (error "veo: generation error:" (.getMessage e))
        {:result/error (str "Video generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.key` in config."}))

(cmd-hook #"veo"
  #".+" veo-cmd)
