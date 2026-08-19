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

(defn- format-stream-message [thinking content cost finished?]
  (let [footer (if finished?
                 (format "\n\nSent via grok-4.6 | Cost: $%s" (format-cost cost))
                 "")
        max-len 1900
        content-len (count content)
        footer-len (count footer)
        allowed-thinking-len (- max-len content-len footer-len 100)]
    (if (and (not (empty? thinking)) (> allowed-thinking-len 100))
      (let [truncated-thinking (if (> (count thinking) allowed-thinking-len)
                                 (str "...\n" (subs thinking (- (count thinking) allowed-thinking-len)))
                                 thinking)
            blockquote (clojure.string/replace truncated-thinking #"(?m)^" "> ")]
        (if (empty? content)
          (str "*Thinking...*\n" blockquote)
          (str "*Thinking...*\n" blockquote "\n\n" content footer)))
      (if (empty? content)
        "*Thinking...*"
        (str content footer)))))

(defn grok-cmd
  "grok <prompt> # ask grok a question"
  {:yb/cat #{:ai}}
  [{:keys [match chat-source]}]
  (if (xai/configured?)
    (try
      (let [prompt match
            {:keys [raw-event]} chat-source
            channel-id (or (:channel-id raw-event) chat/*target*)
            msg-id (:id raw-event)
            on-discord (discord?)]
        (if (and on-discord channel-id msg-id)
          (let [thread-channel (start-thread! channel-id msg-id prompt)]
            (binding [chat/*target* thread-channel]
              (let [thinking-msg @(discord/create-message! (rest-conn) thread-channel :content "*Thinking...*")
                    message-id (:id thinking-msg)]
                (if-not message-id
                  (info "Could not create initial message on Discord thread")
                  (try
                    (let [thinking (atom "")
                          content (atom "")
                          cost-atom (atom 0.0)
                          last-update (atom (System/currentTimeMillis))]
                      (xai/generate-text-stream
                        prompt
                        (fn [{:keys [type text usage done]}]
                          (cond
                            (= type :thinking) (swap! thinking str text)
                            (= type :content) (swap! content str text)
                            (= type :usage) (let [prompt-tokens (get usage :prompt_tokens 0)
                                                  completion-tokens (get usage :completion_tokens 0)
                                                  raw-cost (+ (* prompt-tokens 0.000002)
                                                              (* completion-tokens 0.000006))]
                                              (reset! cost-atom (/ (Math/round (* raw-cost 1000000.0)) 1000000.0)))
                            :else nil)
                          (let [now (System/currentTimeMillis)]
                            (when (or done (>= (- now @last-update) 1000))
                              (reset! last-update now)
                              (let [formatted-msg (format-stream-message @thinking @content @cost-atom (true? done))]
                                (try
                                  @(discord/edit-message! (rest-conn) thread-channel message-id :content formatted-msg)
                                  (catch Exception e
                                    (info "Failed to edit Discord message during stream:" (.getMessage e))))))))))
                    (catch Exception e
                      (error "grok: text generation error:" (.getMessage e))
                      (try
                        @(discord/edit-message! (rest-conn) thread-channel message-id :content (str "Text generation failed: " (.getMessage e)))
                        (catch Exception edit-err
                          (info "Failed to edit Discord message after error:" (.getMessage edit-err)))))))))
            (chat/suppress {}))
          (let [_ (info "grok: generating text for prompt:" prompt)
                {:keys [text cost]} (xai/generate-text prompt)
                footer (format "\n\nSent via grok-4.6 | Cost: $%s" (format-cost cost))
                response-text (str text footer)]
            {:result/value response-text
             :result/data {:prompt prompt :response text}})))
      (catch Exception e
        (error "grok: text generation error:" (.getMessage e))
        {:result/error (str "Text generation failed: " (.getMessage e))}))
    {:result/error
     "xAI API is not configured. Set `xai.key` in config."}))

(cmd-hook #"grok"
  #".+" grok-cmd)
