(ns yetibot.core.commands.bagif
  (:require [clojure.string :as s]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(def ^:private gif-system-prompt
  "You are a GIF generator. Generate animated GIFs based on the user's prompt.
The generated GIFs should be funny, high-quality, and relevant to the user's input.
When the user provides a prompt, create a short, looping, animated GIF that
captures the essence of the prompt.

Make the GIF funny, engaging, and shareable.")

(defn- build-gif-prompt
  [input]
  (str "Create a GIF with this text: " input))

(defn bagif-cmd
  "bagif <prompt> # generate a gif using AI

   Examples:
   bagif homer simpson backing into a bush
   bagif a cat playing a keyboard
   bagif mind blown"
  {:yb/cat #{:img :gif}}
  [{:keys [match chat-source]}]
  (if (gemini/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)
            gif-prompt (build-gif-prompt prompt)
            _ (info "bagif: generating gif for:" prompt
                    "with" (count image-urls) "input image(s)")
            image (gemini/generate-image gif-prompt gif-system-prompt image-urls)
            id (store-image! image)
            base-url (gemini/yetibot-base-url)
            image-url (format "%s/generated-images/%s.gif" base-url id)]
        (info "bagif: gif generated successfully, serving at" image-url)
        {:result/value image-url
         :result/data {:id id :prompt match :url image-url}})
      (catch Exception e
        (error "bagif: Gemini gif generation error:" (.getMessage e))
        {:result/error (str "GIF generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.key` in config."}))

(cmd-hook #"bagif"
  #".+" bagif-cmd)
