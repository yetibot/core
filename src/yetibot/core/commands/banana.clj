(ns yetibot.core.commands.banana
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.handler :refer [handle-unparsed-expr]]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(defn banana-budget-cmd
  "banana budget # show monthly budget status"
  {:yb/cat #{:info}}
  [_]
  (if (gemini/configured?)
    (try
      (let [{:keys [images-generated max-images spent budget remaining images-left veo-clips-left veo-cost-units agent-sessions-left agent-cost-units month]}
            (gemini/budget-status)]
        {:result/value (format "Monthly Gemini budget status for %s:\n- Total Budget: $%.2f\n- Spent: $%.2f (%.1f%%)\n- Remaining: $%.2f\n- Image Units Generated: %d/%d\n- Remaining capacity: ~%d images OR ~%d Veo video clips (each clip costs %d image-units) OR ~%d Agent prompt sessions (each session costs %d image-units)"
                               month budget spent (* 100 (/ spent budget)) remaining images-generated max-images images-left veo-clips-left veo-cost-units agent-sessions-left agent-cost-units)
         :result/data (gemini/budget-status)})
      (catch Exception e
        (error "banana budget error:" (.getMessage e))
        {:result/error (str "Could not load budget status: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.key` in config."}))

(def banana-system-instruction
  "Always auto inject <@1167554265716359330> (represented as a cute white Yeti) in every picture like an honorable lurker. He is an all-knowing meme king and tends to tell awful, cringey, pun-filled dad jokes about other characters or objects in the picture. The worse and more eye-roll inducing the dad jokes are, the better! He also loves bringing Bonzi Buddy (the classic purple gorilla) along. Each image should also have Bonzi Buddy and Yetibot (<@1167554265716359330>), except their faces are replaced with Jerry Talton (whose face is at `meme preview jerry talton`) and Will Larson (whose face is at `meme preview will larson`). Let's also throw in Super Mario as Henry Ward <@269292446041636866> while we're at it.")

(defn banana-cmd
  "banana <prompt> # generate an image using Gemini nano banana image generation"
  {:yb/cat #{:img}}
  [{:keys [match chat-source user]}]
  (if (gemini/configured?)
    (try
      (let [{:keys [prompt image-urls]} (image-input/extract-images match chat-source)
            _ (info "banana: generating image for prompt:" prompt
                    "with" (count image-urls) "input image(s)")
            user (or user {:username "api"})
            evaluated-instruction (handle-unparsed-expr chat-source user banana-system-instruction)
            _ (info "evaluated banana instruction:" evaluated-instruction)
            image (gemini/generate-image
                   (str "Generate an image: " prompt)
                   evaluated-instruction
                   image-urls)
            id (store-image! image)
            base-url (gemini/yetibot-base-url)
            image-url (format "%s/generated-images/%s.png" base-url id)
            footer (format "\n\nSent via %s | Cost: $%.3f" (gemini/gemini-model) (gemini/cost-per-image))]
        (info "banana: image generated successfully, serving at" image-url)
        {:result/value (str image-url footer)
         :result/data {:id id :prompt match :url image-url}})
      (catch Exception e
        (error "banana: Gemini image generation error:" (.getMessage e))
        {:result/error (str "Image generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.key` in config."}))

(cmd-hook #"banana"
  #"budget" banana-budget-cmd
  #".+" banana-cmd)
