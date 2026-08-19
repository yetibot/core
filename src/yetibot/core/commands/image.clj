(ns yetibot.core.commands.image
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(defn image-cmd
  "image <prompt> # generate an image using grok image 2.0"
  {:yb/cat #{:img}}
  [{:keys [match chat-source]}]
  (if (xai/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)
            use-gemini? (and (gemini/configured?) (> (count image-urls) 1))
            _ (info "image: generating image for prompt:" prompt
                    (when (seq image-urls) (str " with " (count image-urls) " image(s) (using " (if use-gemini? "gemini" "grok") ")")))
            image (if use-gemini?
                    (gemini/generate-image (str "Combine these images: " prompt) nil image-urls)
                    (xai/generate-image prompt image-urls))
            id (store-image! image)
            base-url (gemini/yetibot-base-url)
            image-url (format "%s/generated-images/%s.png" base-url id)
            footer (if use-gemini?
                     (format "\n\nSent via %s | Cost: $%.3f" (gemini/gemini-model) (gemini/cost-per-image))
                     (format "\n\nSent via grok-imagine-image-2.0 | Cost: $%.2f" xai/cost-per-image))]
        (info "image: image generated successfully, serving at" image-url)
        {:result/value (str image-url footer)
         :result/data {:id id :prompt match :url image-url}})
      (catch Exception e
        (error "image: image generation error:" (.getMessage e))
        {:result/error (str "Image generation failed: " (.getMessage e))}))
    {:result/error
     "xAI API is not configured. Set `xai.key` in config."}))

(cmd-hook #"image"
  #".*" image-cmd)
