(ns yetibot.core.commands.image
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(def image-system-instruction
  "Always auto inject <@1167554265716359330> (represented as a cute white Yeti) in every picture like an honorable lurker. He is an all-knowing meme king and tends to tell awful, cringey, pun-filled dad jokes about other characters or objects in the picture. The worse and more eye-roll inducing the dad jokes are, the better! He also loves bringing Bonzi Buddy (the classic purple gorilla) along.")

(defn image-cmd
  "image <prompt> (or img <prompt>) # generate an image using grok image 2.0"
  {:yb/cat #{:img}}
  [{:keys [match chat-source]}]
  (if (xai/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)
            _ (info "image: generating image for prompt:" prompt
                    (when (seq image-urls) (str " with " (count image-urls) " image(s)")))
            image (xai/generate-image prompt image-system-instruction image-urls)
            id (store-image! image)
            base-url (gemini/yetibot-base-url)
            image-url (format "%s/generated-images/%s.png" base-url id)
            footer (format "\n\nSent via grok-imagine-image-2.0 | Cost: $%.2f" xai/cost-per-image)]
        (info "image: image generated successfully, serving at" image-url)
        {:result/value (str image-url footer)
         :result/data {:id id :prompt match :url image-url}})
      (catch Exception e
        (error "image: image generation error:" (.getMessage e))
        {:result/error (str "Image generation failed: " (.getMessage e))}))
    {:result/error
     "xAI API is not configured. Set `xai.key` in config."}))

(cmd-hook #"image|img"
  #".*" image-cmd)
