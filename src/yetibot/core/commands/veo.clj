(ns yetibot.core.commands.veo
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(defn veo-cmd
  "veo <prompt> # generate a short AI video with Veo

   Examples:
   veo a cat jumping
   veo a robot dancing in times square
   veo mind blown explosion"
  {:yb/cat #{:img :gif}}
  [{:keys [match]}]
  (if (gemini/configured?)
    (try
      (info "veo: generating video for:" match)
      (let [video (gemini/generate-video match)
            id (store-image! video)
            url (format "%s/generated-images/%s.mp4" (gemini/yetibot-base-url) id)]
        (info "veo: video generated, serving at" url)
        {:result/value url
         :result/data {:id id :prompt match :url url}})
      (catch Exception e
        (error "veo: generation error:" (.getMessage e))
        {:result/error (str "Video generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.key` in config."}))

(cmd-hook #"veo"
  #".+" veo-cmd)
