(ns yetibot.core.commands.agent
  "A meme-loving chat agent. `agent <prompt>` answers the teammate's request with
   a fast Kimi model, reached through a Cloudflare AI Gateway for unified
   observability and cost control. Yetibot relays the answer into a chat thread,
   in the playful persona of Bonzi Buddy.

   On Discord the agent works inside a thread spun off the triggering message,
   so a team can keep replying and re-trigger `agent` to iterate; the thread is
   fed back as context. On other adapters it degrades to plain in-channel
   replies."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.data.json :as json]
   [discljord.messaging :as discord]
   [taoensso.timbre :refer [debug info warn error]]
   [yetibot.core.adapters.adapter :as a]
   [yetibot.core.chat :as chat]
   [yetibot.core.config :refer [get-config]]
   [yetibot.core.db :as db]
   [yetibot.core.db.agent-run :as agent-run]
   [yetibot.core.db.alias :as db.alias]
   [yetibot.core.hooks :refer [cmd-hook]]
   [yetibot.core.interpreter :as interp]
   [yetibot.core.models.help :as help]
   [yetibot.core.handler :refer [record-and-run-raw]]
   [yetibot.core.util.ai-gateway :as ai-gateway]
   [yetibot.core.util.gemini :as gemini])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Config — the agent talks to a Cloudflare AI Gateway (see util.ai-gateway).
;; The model lives under [:agent :model]; budget knobs stay in util.gemini's
;; shared monthly ledger.
;; ---------------------------------------------------------------------------

(s/def ::str string?)
(s/def ::id (s/or :string string? :number number?))

(defn- config-str [path]
  (:value (get-config ::str path)))

;; The agent's model, routed through the gateway. Defaults to a fast Kimi model;
;; override with [:agent :model]. The old [:gemini :agent :model] is honored too.
(defn model []
  (or (config-str [:agent :model])
      (config-str [:gemini :agent :model])
      "kimi-k2.5"))

(defn- config-num [path default]
  (let [v (:value (get-config ::id path))]
    (cond
      (number? v) (long v)
      (string? v) (try (Long/parseLong v) (catch Exception _ default))
      :else default)))

;; Wall-clock ceiling on a single agent reply (the gateway HTTP call's socket
;; timeout), configurable under [:agent :timeout-ms].
(defn agent-timeout-ms [] (config-num [:agent :timeout-ms] 900000))

;; Restart resilience: an in-flight run is persisted and, after a restart that
;; killed it, re-dispatched on the next boot.
;;   max-attempts    - total runs (original + retries) before giving up
;;   resume-stale-ms - skip resuming a run older than this (default 6h)
;;   resume-ready-ms - how long to wait at boot for the DB + an adapter to be live
;;   resume-stagger-ms - gap between resumed dispatches (2 cores, uncapped concurrency)
(defn agent-max-attempts [] (config-num [:agent :max-attempts] 2))
(defn agent-resume-stale-ms [] (config-num [:agent :resume-stale-ms] 21600000))
(defn agent-resume-ready-ms [] (config-num [:agent :resume-ready-ms] 60000))
(defn agent-resume-stagger-ms [] (config-num [:agent :resume-stagger-ms] 3000))

(defn configured?
  "Available only when the Cloudflare AI Gateway is configured."
  []
  (ai-gateway/configured?))

;; ---------------------------------------------------------------------------
;; Persona — Bonzi Buddy voice for the agent's chat messages only.
;; ---------------------------------------------------------------------------

;; Yetibot is the middleman. One transient status message shows that work is in
;; flight; it's deleted at the end and replaced by a clean summary.

(defn say-working
  "Transient status message, deleted once the agent returns its final answer."
  []
  "🐵 Bonzi Buddy is swinging into action! Please wait a moment…")

(defn say-final
  "The clean final reply: the agent's answer plus links to any URLs it cited."
  [summary pr-urls]
  (str (if (string/blank? summary) "✅ done." (str "✅ " summary))
       (when (seq pr-urls)
         (str "\n\n🔗 " (string/join "  •  " (distinct pr-urls))))))

(defn say-broken [msg]
  (str "⚠️ agent error: " msg))

(defn say-timeout [minutes]
  (str "⏰ timed out after " minutes " min — try a smaller ask?"))

(defn say-unconfigured []
  (str "🍌 Oh no! My banana tank is empty (need a Cloudflare AI Gateway + model key) so I can't help you yet! 🍌"))

(defn say-resuming []
  "🐵 Bonzi got bumped by a reboot, but I'm swinging back into action!…")

(defn say-gave-up []
  "💀 Bonzi got too dizzy from restarts — please try again in a bit!")

(defn say-stale []
  "💤 Bonzi fell asleep waiting — ask again?")

(defn resume-request
  "Prefix a request for a resumed run so the agent picks the interrupted ask back
   up rather than starting over."
  [request]
  (str "(Your previous reply to this was interrupted by a restart before it could "
       "be sent. Please answer the request below.)\n\n"
       request))

;; ---------------------------------------------------------------------------
;; Safety
;; ---------------------------------------------------------------------------

(defn redact
  "Strip embedded credentials (e.g. a token in a URL) from a string before it
   reaches chat or logs."
  [s]
  (when s
    (-> s
        (string/replace #"(://[^:/@\s]+:)[^@\s]+(@)" "$1***$2")
        (string/replace #"(://)[^:/@\s]+(@)" "$1***$2")
        (string/replace #"gh[pousr]_[A-Za-z0-9]{20,}" "***"))))

(defn pr-urls
  "GitHub pull request URLs mentioned in text, de-duplicated."
  [text]
  (->> (re-seq #"https://github\.com/[\w.-]+/[\w.-]+/pull/\d+" (or text ""))
       distinct vec))

(defn mention-glossary
  "A note mapping each Discord mention in the message to the person's server
   display name and their <@id> token. The request keeps its <@id> tokens so the
   agent's reply can ping people (Discord renders their server name); this just
   tells the agent who's who. Empty string when there are no mentions."
  [mentions]
  (let [lines (for [{:keys [id username global-name member]} mentions
                    :when id]
                (str "• <@" id "> is " (or (:nick member) global-name username (str "user " id))))]
    (if (seq lines)
      (str "People referenced in the request (write their <@id> token verbatim to "
           "@-mention/ping them in your reply — that shows their server name):\n"
           (string/join "\n" lines))
      "")))

;; ---------------------------------------------------------------------------
;; The agent prompt — a chat exchange (system persona + the teammate's request)
;; ---------------------------------------------------------------------------

(defn build-system-prompt
  "The agent's persona and ground rules. It operates purely through chat — it
   cannot run commands, browse, or open pull requests."
  []
  (str "You are Yetibot — the team's helpful assistant bot, appearing as @Yetibot "
       "in their chat, in the brief, playful, and cheerful persona of Bonzi Buddy, "
       "the classic purple gorilla Windows assistant (keep every fact exact).\n\n"
       "Answer the teammate's request directly and concisely: questions, code, "
       "debugging, reviews, ideas. You operate purely through chat — you cannot run "
       "commands, browse the web, or open pull requests, so never claim to have done "
       "so. If a request needs an action you can't take, say so plainly. If you can't "
       "tell what the request refers to, ask briefly for the missing detail rather "
       "than inventing an answer.\n\n"
       "Reply with ONLY your final answer — no step-by-step narration or lists of "
       "justifications. When you address a person, write their Discord mention token "
       "<@id> verbatim (e.g. <@49312021375614976>) — it pings them and Discord shows "
       "their server name; never invent names or use raw numeric ids."))

(defn build-user-message
  "The teammate's request, with the mention glossary and thread context (both
   reference-only) folded in."
  [request context mentions]
  (str (when-not (string/blank? mentions) (str mentions "\n\n"))
       (when-not (string/blank? context)
         (str "This thread's conversation so far, for REFERENCE ONLY — background, "
              "not a task list. Use it only to resolve what the request refers to "
              "(e.g. a \"retry\" or follow-up points back to an earlier ask here):\n"
              "────\n" (string/trim context) "\n────\n\n"))
       "The teammate's request:\n" (string/trim request)))

(defn run-model
  "Send one chat exchange through the gateway and return the agent's reply text.
   Throws on a non-2xx gateway response or a transport timeout."
  [request context mentions]
  (:text (ai-gateway/chat {:model (model)
                           :timeout-ms (agent-timeout-ms)
                           :messages [{:role "system" :content (build-system-prompt)}
                                      {:role "user"
                                       :content (build-user-message request context mentions)}]})))

;; ---------------------------------------------------------------------------
;; Discord thread plumbing (guarded; degrades to plain replies elsewhere)
;; ---------------------------------------------------------------------------

(defn- discord? []
  (and chat/*adapter*
       (= "discord" (some-> (a/platform-name chat/*adapter*) string/lower-case))))

(defn- rest-conn [] (:rest @(:conn chat/*adapter*)))

(defn- start-thread!
  "Spin a Discord thread off the triggering message; returns the thread channel
   id, or the original channel id if threading isn't possible."
  [channel-id message-id title]
  (or (try
        (:id @(discord/start-thread-with-message!
               (rest-conn) channel-id message-id (subs title 0 (min 90 (count title))) 1440))
        (catch Exception e (debug "start-thread! fell back:" (.getMessage e)) nil))
      channel-id))

(defn- delete-msg!
  "Delete a Discord message (best-effort)."
  [channel-id message-id]
  (try @(discord/delete-message! (rest-conn) channel-id message-id)
       (catch Exception e (debug "delete-msg! failed:" (.getMessage e)))))

(defn- all-channel-messages
  "Every message in a channel/thread, paginating past Discord's 100-per-call cap
   (bounded for safety). Discord returns newest-first; callers sort as needed."
  [channel-id]
  (loop [before nil acc []]
    (let [opts (concat [:limit 100] (when before [:before before]))
          batch (vec @(apply discord/get-channel-messages! (rest-conn) channel-id opts))
          acc' (into acc batch)]
      (if (or (< (count batch) 100) (>= (count acc') 500))
        acc'
        (recur (:id (peek batch)) acc')))))

(defn- thread-context
  "The full thread conversation, oldest-first, prefixed with the thread topic —
   the message that opened the thread, i.e. the original request. Capturing the
   whole thread lets a follow-up like \"retry\" resolve to the original ask."
  [channel-id]
  (try
    (let [channel @(discord/get-channel! (rest-conn) channel-id)
          type (:type channel)]
      (if (not (#{10 11 12} type))
        ""
        (let [lines (->> (all-channel-messages channel-id)
                         (sort-by :timestamp)
                         (map (fn [m] (str (get-in m [:author :username]) ": " (:content m))))
                         (remove string/blank?))]
          (if (<= (count lines) 1)
            ""
            (let [topic (:name channel)]
              (string/join "\n" (cond->> lines
                                  (not (string/blank? topic)) (cons (str "[thread topic] " topic)))))))))
    (catch Exception e (debug "thread-context failed:" (.getMessage e)) "")))

;; ---------------------------------------------------------------------------
;; Persistence — survive a restart that kills an in-flight run
;; ---------------------------------------------------------------------------

(defn- record-run!
  "Persist an in-flight run so a restart can resume it; returns its run-id, or nil
   if persistence failed (the run still proceeds, it just won't be resumable)."
  [run]
  (let [run-id (str (java.util.UUID/randomUUID))]
    (try
      (agent-run/create (assoc run :run-id run-id))
      run-id
      (catch Exception e
        (warn "agent run persist failed (run will not be resumable):" (.getMessage e))
        nil))))

(defn- clear-run!
  "Delete a run's record once it reaches any terminal outcome (best-effort)."
  [run-id]
  (when run-id
    (try
      (when-let [{:keys [id]} (first (agent-run/query {:where/map {:run-id run-id}}))]
        (agent-run/delete id))
      (catch Exception e (warn "agent run clear failed:" (.getMessage e))))))

(defn resume-action
  "Decide what to do with a run left in-flight by a restart: :give-up once it has
   used up its attempts, :stale when it's older than the cutoff, else :resume."
  [attempts age-ms max-attempts stale-ms]
  (cond
    (>= attempts max-attempts) :give-up
    (> age-ms stale-ms) :stale
    :else :resume))

;; ---------------------------------------------------------------------------
;; Command
;; ---------------------------------------------------------------------------

(defn run-agent
  "Async body: send the request through the gateway, then delete the transient
   status message and post one clean final reply. No intermediate narration."
  [{:keys [request target context-channel on-discord status-id mentions run-id]}]
  (binding [chat/*target* target]
    (try
      (gemini/check-budget!)
      (let [context (when on-discord (thread-context context-channel))
            response (run-model request context mentions)]
        (gemini/record-image-generated! (gemini/agent-cost-units))
        (when (and on-discord status-id) (delete-msg! target status-id))
        (chat/send-msg (if (string/blank? response)
                         (say-final "done." nil)
                         (say-final response (pr-urls response)))))
      (catch java.net.SocketTimeoutException _
        (when (and on-discord status-id) (delete-msg! target status-id))
        (chat/send-msg (say-timeout (quot (agent-timeout-ms) 60000))))
      (catch Exception e
        (error "agent command failed" e)
        (when (and on-discord status-id) (delete-msg! target status-id))
        (chat/send-msg (say-broken (redact (.getMessage e)))))
      (finally
        (clear-run! run-id)))))

(defn agent-cmd
  "agent <prompt> # ask the Kimi-powered agent and reply with its answer"
  {:yb/cat #{:util}}
  [{[_ request] :match chat-source :chat-source}]
  (cond
    (not (configured?)) (say-unconfigured)
    :else
    (let [adapter chat/*adapter*
          {:keys [raw-event]} chat-source
          ;; keep <@id> tokens in the request; give the agent a name glossary so its
          ;; reply pings the right people (Discord renders their server names)
          mentions (mention-glossary (:mentions raw-event))
          channel (or (:channel-id raw-event) chat/*target*)
          msg-id (:id raw-event)
          on-discord (discord?)
          ;; on Discord, work inside a thread off the triggering message
          target (if (and on-discord channel msg-id)
                   (start-thread! channel msg-id request)
                   chat/*target*)
          ;; transient status; deleted when the final answer is posted
          status-id (:id (binding [chat/*target* target] (chat/send-msg (say-working))))
          ;; persist the run so a restart that kills it can resume it
          run-id (record-run! {:request request
                               :target (some-> target str)
                               :context-channel (some-> channel str)
                               :status-id (some-> status-id str)
                               :adapter-uuid (some-> adapter a/uuid str)
                               :mentions mentions
                               :on-discord on-discord})]
      (future
        (binding [chat/*adapter* adapter]
          (run-agent {:request request :target target :context-channel channel
                      :on-discord on-discord :status-id status-id :mentions mentions
                      :run-id run-id})))
      ;; the answer is posted out of band; suppress the framework's reply
      (chat/suppress {}))))

;; ---------------------------------------------------------------------------
;; Resume — re-dispatch runs a restart left in-flight
;; ---------------------------------------------------------------------------

(defonce ^:private boot-time (System/currentTimeMillis))

(defn- adapter-by-uuid
  "The live adapter whose uuid matches a persisted run's adapter-uuid."
  [uuid]
  (some #(when (= uuid (some-> % a/uuid str)) %) (a/active-adapters)))

(defn- adapter-ready? []
  (boolean (some #(try (a/connected? %) (catch Exception _ false))
                 (a/active-adapters))))

(defn- await-ready
  "Block (up to timeout-ms) until the DB is connected and an adapter is live, so a
   resumed run can read its state and post a reply. Returns true once ready."
  [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (and @db/connected? (adapter-ready?)) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 1000) (recur))))))

(defn- say-to-run!
  "Post msg into a run's thread on its adapter, clearing the dangling status first."
  [{:keys [target adapter-uuid on-discord status-id]} msg]
  (when-let [adapter (adapter-by-uuid adapter-uuid)]
    (binding [chat/*adapter* adapter chat/*target* target]
      (when (and on-discord status-id) (delete-msg! target status-id))
      (chat/send-msg msg))))

(defn- dispatch-resume!
  "Re-run an interrupted run, restoring its adapter + thread and nudging the agent
   to pick the request back up."
  [{:keys [run-id request target context-channel on-discord status-id mentions
           adapter-uuid]}]
  (if-let [adapter (adapter-by-uuid adapter-uuid)]
    (do
      (binding [chat/*adapter* adapter chat/*target* target]
        (chat/send-msg (say-resuming)))
      (future
        (binding [chat/*adapter* adapter]
          (run-agent {:request (resume-request request) :target target
                      :context-channel context-channel :on-discord on-discord
                      :status-id status-id :mentions mentions :run-id run-id}))))
    (warn "cannot resume agent run; adapter gone:" adapter-uuid)))

(defn- resume-run!
  [{:keys [id attempts created-at] :as row} now]
  (case (resume-action attempts (- now (.getTime created-at))
                       (agent-max-attempts) (agent-resume-stale-ms))
    :give-up (do (say-to-run! row (say-gave-up)) (agent-run/delete id))
    :stale   (do (say-to-run! row (say-stale)) (agent-run/delete id))
    :resume  (do (agent-run/update-where {:run-id (:run-id row)}
                                         {:attempts (inc attempts)})
                 (dispatch-resume! row))))

(defn resume-interrupted-runs!
  "On boot, re-dispatch any agent runs a restart left in-flight. Runs in a future
   so it never blocks startup; staggers dispatches to spare the box's few cores."
  []
  (future
    (try
      (when (await-ready (agent-resume-ready-ms))
        (let [rows (->> (agent-run/find-all)
                        (filter #(< (.getTime (:created-at %)) boot-time)))]
          (when (seq rows)
            (info "resuming" (count rows) "interrupted agent run(s)"))
          (let [now (System/currentTimeMillis)]
            (doseq [row rows]
              (try (resume-run! row now)
                   (catch Exception e (error "resume failed for run" (:run-id row) e)))
              (Thread/sleep (agent-resume-stagger-ms))))))
      (catch Exception e (error "resume-interrupted-runs! failed" e)))))

(defn agent-list-commands-cmd
  "agent list-commands # print all available commands as JSON"
  {:yb/cat #{:util}}
  [_]
  (let [commands (sort (keys (help/get-docs)))]
    {:result/value (json/write-str {:commands commands})}))

(defn agent-list-aliases-cmd
  "agent list-aliases # print all configured aliases as JSON"
  {:yb/cat #{:util}}
  [_]
  (let [aliases (try
                  (db.alias/find-all)
                  (catch Exception _
                    (map (fn [[k v]] {:cmd-name k :cmd (first v)}) (help/get-alias-docs))))
        res {:aliases (map #(select-keys % [:cmd-name :cmd]) aliases)}]
    {:result/value (json/write-str res)}))

(defn agent-run-cmd
  "agent run <command> # run the given yetibot command and print result"
  {:yb/cat #{:util}}
  [{[_ cmd-str] :match}]
  (let [user {:username "agent" :name "agent" :id "agent"}
        chat-source {:adapter :agent :room "agent-room"}
        results (binding [interp/*chat-source* chat-source]
                  (record-and-run-raw cmd-str user nil {:record-yetibot-response? false}))
        out (string/join "\n" (map #(or (:result %) (:error %)) results))]
    {:result/value out}))

;; Register only when the Cloudflare AI Gateway is configured.
(when (configured?)
  (cmd-hook #"agent"
            #"list-commands" agent-list-commands-cmd
            #"list-aliases" agent-list-aliases-cmd
            #"run\s+(.+)" agent-run-cmd
            #"(?s)(.+)" agent-cmd)
  (resume-interrupted-runs!))
