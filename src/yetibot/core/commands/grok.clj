(ns yetibot.core.commands.grok
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.xai :as xai]))

(defn grok-cmd
  "grok <prompt> # ask grok a question"
  {:yb/cat #{:ai}}
  [{:keys [match]}]
  (if (xai/configured?)
    (try
      (let [prompt match
            _ (info "grok: generating text for prompt:" prompt)
            text (xai/generate-text prompt)
            footer (format "\n\nSent via grok-4.6 | Cost: $%.3f" (xai/cost-per-prompt))]
        {:result/value (str text footer)
         :result/data {:prompt prompt :response text}})
      (catch Exception e
        (error "grok: text generation error:" (.getMessage e))
        {:result/error (str "Text generation failed: " (.getMessage e))}))
    {:result/error
     "xAI API is not configured. Set `xai.key` in config."}))

(cmd-hook #"grok"
  #".+" grok-cmd)
