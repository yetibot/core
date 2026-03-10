(ns yetibot.core.commands.banana
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(defn banana-cmd
  "banana <prompt> # generate an image using Gemini nano banana image generation
   Supports image inputs:
     banana make this person a superhero @username
     banana add explosions to this <attached image>
     banana remix this https://example.com/image.png"
  {:yb/cat #{:img}}
  [{:keys [match chat-source]}]
  (if (gemini/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)]
        (info "banana: generating image for prompt:" prompt
              (when (seq image-urls) (str "with " (count image-urls) " image(s)")))
        (let [image (gemini/generate-image
                     (str "Generate an image: " prompt) nil image-urls)
              id (store-image! image)
              base-url (gemini/yetibot-base-url)
              image-url (format "%s/generated-images/%s.png" base-url id)]
          (info "banana: image generated successfully, serving at" image-url)
          {:result/value image-url
           :result/data {:id id :prompt match :url image-url}}))
      (catch Exception e
        (error "banana: Gemini image generation error:" (.getMessage e))
        {:result/error (str "Image generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.api.key` in config."}))

(cmd-hook #"banana"
  #".+" banana-cmd)
