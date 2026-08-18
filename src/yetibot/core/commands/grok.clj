(ns yetibot.core.commands.grok
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.xai :as xai]))

(defn- format-cost
  "Format cost to 4 decimal places if it's less than $0.01, otherwise 2 decimal places."
  [cost]
  (cond
    (nil? cost) "0.00"
    (zero? cost) "0.00"
    (< cost 0.01) (format "%.4f" cost)
    :else (format "%.2f" cost)))

(defn grok-cmd
  "grok <prompt> # ask grok a question"
  {:yb/cat #{:ai}}
  [{:keys [match]}]
  (if (xai/configured?)
    (try
      (let [prompt match
            _ (info "grok: generating text for prompt:" prompt)
            {:keys [text cost]} (xai/generate-text prompt)
            footer (format "\n\nSent via grok-4.6 | Cost: $%s" (format-cost cost))]
        {:result/value (str text footer)
         :result/data {:prompt prompt :response text}})
      (catch Exception e
        (error "grok: text generation error:" (.getMessage e))
        {:result/error (str "Text generation failed: " (.getMessage e))}))
    {:result/error
     "xAI API is not configured. Set `xai.key` in config."}))

(cmd-hook #"grok"
  #".+" grok-cmd)
