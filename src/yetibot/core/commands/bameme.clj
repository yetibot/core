(ns yetibot.core.commands.bameme
  (:require [clojure.string :as s]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(def ^:private meme-system-prompt
  "You are a meme image generator. Generate images that look like classic
internet memes. The image MUST include:

1. A funny, relevant background image or scene that matches the meme concept
2. Bold white text with black outline (Impact font style) overlaid on the image
3. Text should be placed at the TOP and/or BOTTOM of the image, like classic memes

When the user provides a meme template name (like 'drake', 'distracted boyfriend',
'expanding brain', 'one does not simply', 'change my mind', etc.), generate an
image in that meme's visual style with the provided text.

When the user provides text separated by '/' characters, treat each segment as a
separate text region on the meme (top/bottom, or panel labels).

When the user provides reference images (photos of people, scenes, etc.), incorporate
those visual elements into the meme. Use the person's likeness or the scene as the
basis for the meme image.

Make the image funny, bold, and immediately recognizable as a meme.")

(defn- build-meme-prompt
  [input]
  (if-let [[_ template text] (re-matches #"(?i)(.+?):\s*(.+)" input)]
    (str "Create a meme in the style of the '" (s/trim template)
         "' meme template with this text: " (s/trim text))
    (str "Create a meme with this text: " input)))

(defn bameme-cmd
  "bameme <template>: <text> # generate a meme image using AI

   Examples:
   bameme drake: writing tests / shipping to prod
   bameme distracted boyfriend: new js framework / my mass project / stability
   bameme one does not simply: deploy on a friday
   bameme this is fine: everything is on fire at work

   With image inputs:
   bameme drake: @alice / @bob (uses their Discord avatars)
   bameme distracted boyfriend: <attach images>
   bameme this person: https://example.com/photo.png

   Or without a template:
   bameme when the code compiles on the first try"
  {:yb/cat #{:img :meme}}
  [{:keys [match chat-source]}]
  (if (gemini/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)
            meme-prompt (build-meme-prompt prompt)
            _ (info "bameme: generating meme for:" prompt
                    (when (seq image-urls) (str "with " (count image-urls) " image(s)")))
            image (gemini/generate-image meme-prompt meme-system-prompt image-urls)
            id (store-image! image)
            base-url (gemini/yetibot-base-url)
            image-url (format "%s/generated-images/%s.png" base-url id)]
        (info "bameme: meme generated successfully, serving at" image-url)
        {:result/value image-url
         :result/data {:id id :prompt match :url image-url}})
      (catch Exception e
        (error "bameme: Gemini meme generation error:" (.getMessage e))
        {:result/error (str "Meme generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.api.key` in config."}))

(cmd-hook #"bameme"
  #".+" bameme-cmd)
