(ns yetibot.core.commands.grok
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(defn grok-cmd
  "grok <prompt> # generate an image using grok image 2.0"
  {:yb/cat #{:img}}
  [{:keys [match chat-source]}]
  (if (xai/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)
            _ (info "grok: generating image for prompt:" prompt)
            image (xai/generate-image prompt)
            id (store-image! image)
            base-url (gemini/yetibot-base-url)
            image-url (format "%s/generated-images/%s.png" base-url id)
            footer "\n\nSent via grok-imagine-image-2.0"]
        (info "grok: image generated successfully, serving at" image-url)
        {:result/value (str image-url footer)
         :result/data {:id id :prompt match :url image-url}})
      (catch Exception e
        (error "grok: image generation error:" (.getMessage e))
        {:result/error (str "Image generation failed: " (.getMessage e))}))
    {:result/error
     "xAI API is not configured. Set `xai.key` in config."}))

(cmd-hook #"grok"
  #".+" grok-cmd)
