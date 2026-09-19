(ns yetibot.core.commands.grok
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.adapters.adapter :as a]
            [yetibot.core.chat :as chat]
            [discljord.messaging :as discord]
            [clojure.string :as string]
            [yetibot.core.commands.agent :as agent]))

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

(defn rest-conn []
  (when-let [conn-atom (:conn chat/*adapter*)]
    (:rest @conn-atom)))

(defn start-thread!
  "Spin a Discord thread off the triggering message; returns the thread channel
   id, or the original channel id if threading isn't possible."
  [channel-id message-id title]
  (or (try
        (:id @(discord/start-thread-with-message!
               (rest-conn) channel-id message-id (subs title 0 (min 90 (count title))) 1440))
        (catch Exception e (info "start-thread! fell back:" (.getMessage e)) nil))
      channel-id))

(defn- clean-grok-prompt [content]
  (if (string? content)
    (string/trim (string/replace content #"^(?i)grok\s*" ""))
    content))

(defn- clean-assistant-response [content]
  (if (string? content)
    (string/trim (string/replace content #"(?s)\n\nSent via grok.*" ""))
    content))

(defn- all-channel-messages
  "The most recent messages in a channel/thread (bounded to 20 for cost efficiency)."
  [channel-id]
  (try
    (if-let [conn (rest-conn)]
      (if-let [fut (discord/get-channel-messages! conn channel-id :limit 20)]
        (vec @fut)
        [])
      [])
    (catch Exception e
      (info "all-channel-messages failed:" (.getMessage e))
      [])))

(defn- get-thread-messages
  "Fetch thread history and format it for xAI chat completions."
  [channel-id triggering-msg-id current-prompt]
  (if-not (rest-conn)
    [{:role "user" :content current-prompt}]
    (try
      (if-let [conn (rest-conn)]
        (if-let [channel-fut (discord/get-channel! conn channel-id)]
          (let [channel @channel-fut
                type (:type channel)]
            (if (not (#{10 11 12} type))
              [{:role "user" :content current-prompt}]
              (let [hist (all-channel-messages channel-id)
                    filtered (remove #(= (:id %) triggering-msg-id) hist)
                    sorted (sort-by :timestamp filtered)
                    formatted (map (fn [m]
                                     (let [is-bot? (or (get-in m [:author :bot])
                                                       (= (:username (:author m)) "Yetibot"))
                                           content (:content m)]
                                       (if is-bot?
                                         {:role "assistant" :content (clean-assistant-response content)}
                                         {:role "user" :content (clean-grok-prompt content)})))
                                   sorted)
                    valid-messages (filter #(not (string/blank? (:content %))) formatted)]
                (conj (vec valid-messages) {:role "user" :content current-prompt}))))
          [{:role "user" :content current-prompt}])
        [{:role "user" :content current-prompt}])
      (catch Exception e
        (info "get-thread-messages failed:" (.getMessage e))
        [{:role "user" :content current-prompt}]))))

(defn- get-thinking-string [seconds]
  (let [num-blocks (inc (mod (dec seconds) 10))
        blocks (apply str (repeat num-blocks "▰"))]
    (str "*thinking* " blocks)))

(defn- format-grok-response [text reasoning]
  (if-not (string/blank? reasoning)
    (str "### 🧠 Thinking Process\n"
         (string/join "\n" (map #(str "> " %) (string/split-lines reasoning)))
         "\n\n"
         text)
    text))

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
            on-discord (discord?)
            payload (if (and on-discord channel-id msg-id)
                      (let [msgs (get-thread-messages channel-id msg-id prompt)]
                        (if (> (count msgs) 1)
                          msgs
                          prompt))
                      prompt)
            _ (info "grok: generating text with payload:" (pr-str payload))]
        (if (and on-discord channel-id msg-id)
          (let [thread-channel (start-thread! channel-id msg-id prompt)
                conn (rest-conn)
                initial-msg (try
                              @(discord/create-message! conn thread-channel :content "*thinking* ▰")
                              (catch Exception e
                                (info "Failed to create initial thinking message:" (.getMessage e))
                                nil))
                msg-id-to-edit (:id initial-msg)
                gen-future (future
                             (try
                               (let [res (xai/generate-text payload)]
                                 {:status :success :result res})
                               (catch Exception e
                                 {:status :error :error e})))
                _ (when msg-id-to-edit
                    (loop [sec 1]
                      (if (realized? gen-future)
                        nil
                        (do
                          (Thread/sleep 1000)
                          (when-not (realized? gen-future)
                            (try
                              @(discord/edit-message! conn thread-channel msg-id-to-edit
                                                      :content (get-thinking-string sec))
                              (catch Exception e
                                (info "Failed to edit thinking message:" (.getMessage e)))))
                          (recur (inc sec))))))
                gen-res @gen-future]
            (if (= (:status gen-res) :success)
              (let [{:keys [text reasoning cost]} (:result gen-res)
                    formatted-text (format-grok-response text reasoning)
                    footer (format "\n\nSent via grok-4.6 | Cost: $%s" (format-cost cost))
                    response-text (str formatted-text footer)]
                (when msg-id-to-edit
                  (try
                    @(discord/delete-message! conn thread-channel msg-id-to-edit)
                    (catch Exception e
                      (info "Failed to delete thinking message:" (.getMessage e)))))
                (binding [chat/*target* thread-channel]
                  (chat/chat-data-structure response-text))
                (chat/suppress {}))
              (let [err (:error gen-res)
                    err-msg (str "Text generation failed: " (.getMessage err))]
                (error "grok: text generation error:" (.getMessage err))
                (when msg-id-to-edit
                  (try
                    @(discord/delete-message! conn thread-channel msg-id-to-edit)
                    (catch Exception e
                      (info "Failed to delete thinking message:" (.getMessage e)))))
                (binding [chat/*target* thread-channel]
                  (chat/chat-data-structure err-msg))
                (chat/suppress {}))))
          (let [{:keys [text reasoning cost]} (xai/generate-text payload)
                formatted-text (format-grok-response text reasoning)
                footer (format "\n\nSent via grok-4.6 | Cost: $%s" (format-cost cost))
                response-text (str formatted-text footer)]
            {:result/value response-text
             :result/data {:prompt prompt :response text}})))
      (catch Exception e
        (error "grok: text generation error:" (.getMessage e))
        {:result/error (str "Text generation failed: " (.getMessage e))}))
    {:result/error
     "xAI API is not configured. Set `xai.key` in config."}))

(defn grok-agent-cmd
  "grok agent <prompt> # run the autonomous agent using Grok's agent powers"
  {:yb/cat #{:util}}
  [{[_ prompt] :match :as opts}]
  (if (agent/configured?)
    (agent/agent-cmd (assoc opts :match [prompt prompt]))
    {:result/error "Agent is not configured."}))

(cmd-hook #"grok"
  #"agent\s+(?s)(.+)" grok-agent-cmd
  #".+" grok-cmd)
