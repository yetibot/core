(ns yetibot.core.commands.weathermap
  (:require [taoensso.timbre :refer [info error]]
            [clojure.string :as string]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.handler :refer [handle-unparsed-expr]]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(defn weathermap-cmd
  "weathermap # generate an image of North America showing wind movements, temperature heat map, and air quality index based on current weather data"
  {:yb/cat #{:img :info}}
  [{:keys [chat-source user]}]
  (if (gemini/configured?)
    (try
      (let [user (or user {:username "api"})
            ;; Retrieve current temperatures for weather locations
            temps-data (handle-unparsed-expr chat-source user "utemps")
            ;; Construct a prompt for Gemini image generation
            prompt (str "A highly detailed, professional weather map of North America showing temperature heat map, wind movements (using beautiful swirling arrows and streamlines), and air quality index overlays. Use modern weather graphics. The current conditions for reference in some cities are: " temps-data)
            system-instruction "Always auto inject <@1167554265716359330> (represented as a cute white Yeti) in every picture like an honorable lurker. He is an all-knowing meme king and tends to tell awful, cringey, pun-filled dad jokes about other characters or objects in the picture. The worse and more eye-roll inducing the dad jokes are, the better! He also loves bringing Bonzi Buddy (the classic purple gorilla) along."
            image (gemini/generate-image prompt system-instruction)
            id (store-image! image)
            base-url (gemini/yetibot-base-url)
            image-url (format "%s/generated-images/%s.png" base-url id)
            footer (format "\n\nSent via %s | Cost: $%.3f" (gemini/gemini-model) (gemini/cost-per-image))]
        (info "weathermap: weather map generated successfully, serving at" image-url)
        {:result/value (str image-url footer)
         :result/data {:id id :prompt prompt :url image-url}})
      (catch Exception e
        (error "weathermap: weather map image generation error:" (.getMessage e))
        {:result/error (str "Weather map generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.key` in config."}))

(cmd-hook #"weathermap"
  _ weathermap-cmd)
