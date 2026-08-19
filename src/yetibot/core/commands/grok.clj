(ns yetibot.core.commands.grok
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.adapters.adapter :as a]
            [yetibot.core.chat :as chat]
            [discljord.messaging :as discord]
            [clojure.string :as string]))

(defn- format-cost
  "Format cost to 4 decimal places if it's less than $0.01, otherwise 2 decimal places."
  [cost]
  (cond
    (nil? cost) "0.00"
    (zero? cost) "0.00"
    (< cost 0.01) (format "%.4f" cost)
    :else (format "%.2f" cost)))

(defn discord?
  "Check if the current chat adapter is Discord."
  []
  (and chat/*adapter*
       (= "discord" (some-> (a/platform-name chat/*adapter*) string/lower-case))))

(defn rest-conn [] (:rest @(:conn chat/*adapter*)))

(defn start-thread!
  "Spin a Discord thread off the triggering message; returns the thread channel
   id, or the original channel id if threading isn't possible."
  [channel-id message-id title]
  (or (try
        (:id @(discord/start-thread-with-message!
               (rest-conn) channel-id message-id (subs title 0 (min 90 (count title))) 1440))
        (catch Exception e (info "start-thread! fell back:" (.getMessage e)) nil))
      channel-id))

(defn grok-cmd
  "grok <prompt> # ask grok a question"
  {:yb/cat #{:ai}}
  [{:keys [match chat-source]}]
  (if (xai/configured?)
    (try
      (let [prompt match
            _ (info "grok: generating text for prompt:" prompt)
            {:keys [text cost]} (xai/generate-text prompt)
            footer (format "\n\nSent via grok-4.6 | Cost: $%s" (format-cost cost))
            response-text (str text footer)
            {:keys [raw-event]} chat-source
            channel-id (or (:channel-id raw-event) chat/*target*)
            msg-id (:id raw-event)
            on-discord (discord?)]
        (if (and on-discord channel-id msg-id)
          (let [thread-channel (start-thread! channel-id msg-id prompt)]
            (binding [chat/*target* thread-channel]
              (chat/chat-data-structure response-text))
            (chat/suppress {}))
          {:result/value response-text
           :result/data {:prompt prompt :response text}}))
      (catch Exception e
        (error "grok: text generation error:" (.getMessage e))
        {:result/error (str "Text generation failed: " (.getMessage e))}))
    {:result/error
     "xAI API is not configured. Set `xai.key` in config."}))

(cmd-hook #"grok"
  #".+" grok-cmd)
