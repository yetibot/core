(ns yetibot.core.commands.agent
  "A meme-loving coding agent. `agent <prompt>` hands the request to the Gemini
   CLI running headlessly as an autonomous agent: Gemini uses the authenticated
   `gh` CLI and `git` to find the right repo(s), make the change, and open pull
   requests itself. Yetibot's job is just to run it and relay what it's doing,
   live, into a chat thread — in a quirky grug/caveman persona.

   On Discord the agent works inside a thread spun off the triggering message,
   so a team can keep replying and re-trigger `agent` to iterate; the thread is
   fed back as context. On other adapters it degrades to plain in-channel
   replies.

   GitHub auth is a GitHub App (preferred) or a static token; either is handed
   to Gemini as GH_TOKEN so its `gh`/`git` calls are authenticated."
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [discljord.messaging :as discord]
   [taoensso.timbre :refer [debug info warn error]]
   [yetibot.core.adapters.adapter :as a]
   [yetibot.core.chat :as chat]
   [yetibot.core.config :refer [get-config]]
   [yetibot.core.hooks :refer [cmd-hook]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.security KeyFactory Signature]
   [java.security.spec PKCS8EncodedKeySpec]
   [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Config
;;
;;   YB_GEMINI_KEY          - Gemini API key (required)
;;   YB_GEMINI_CLI          - path to the gemini CLI binary (default "gemini")
;;
;; GitHub auth, in order of preference:
;;   GitHub App: YB_GITHUB_APP_ID + YB_GITHUB_APP_PRIVATE_KEY
;;               (+ optional YB_GITHUB_APP_INSTALLATION_ID)
;;   Static token: YB_GITHUB_TOKEN
;; ---------------------------------------------------------------------------

(s/def ::str string?)
(s/def ::id (s/or :string string? :number number?))

(defn- config-str [path]
  (:value (get-config ::str path)))

;; The strongest Gemini coding model.
(def model "gemini-2.5-pro")

(defn gemini-key [] (config-str [:gemini :key]))
(defn cli-bin [] (or (config-str [:gemini :cli]) "gemini"))

(defn- config-num [path default]
  (let [v (:value (get-config ::id path))]
    (cond
      (number? v) (long v)
      (string? v) (try (Long/parseLong v) (catch Exception _ default))
      :else default)))

;; How long the headless Gemini run may take before the bot kills it, and how
;; many agent turns it may take. Both configurable under [:gemini :agent].
(defn agent-timeout-ms [] (config-num [:gemini :agent :timeout-ms] 300000))
(defn agent-max-turns [] (config-num [:gemini :agent :max-turns] 50))
;; how long a leftover scratch dir may linger before the sweep reaps it (1 day)
(defn agent-workdir-max-age-ms [] (config-num [:gemini :agent :workdir-max-age-ms] 86400000))

(defn github-pat [] (config-str [:github :token]))

(defn app-id []
  (some-> (:value (get-config ::id [:github :app :id])) str))

(defn app-private-key
  "The GitHub App's PEM private key. Tolerates env-var encoded newlines (\\n)."
  []
  (some-> (config-str [:github :app :private-key])
          (string/replace "\\n" "\n")))

(defn app-configured? []
  (boolean (and (not (string/blank? (app-id)))
                (not (string/blank? (app-private-key))))))

(defn github-auth-configured? []
  (or (app-configured?) (not (string/blank? (github-pat)))))

(defn configured?
  "Available only when Gemini and some GitHub auth are set."
  []
  (boolean (and (not (string/blank? (gemini-key)))
                (github-auth-configured?))))

;; ---------------------------------------------------------------------------
;; Persona — grug/caveman/meme voice for the agent's chat messages only.
;; ---------------------------------------------------------------------------

;; Yetibot is the middleman. One transient status message shows the latest step
;; while Gemini works; it's deleted at the end and replaced by a clean summary.

(defn say-working
  "Transient status message, deleted once Gemini returns its final answer."
  []
  "🦴 grug on it… 🧠⚡")

(defn say-final
  "The clean final reply: Gemini's summary plus links to any relevant PRs."
  [summary pr-urls]
  (str (if (string/blank? summary) "✅ done." (str "✅ " summary))
       (when (seq pr-urls)
         (str "\n\n🔗 " (string/join "  •  " (distinct pr-urls))))))

(defn say-broken [msg]
  (str "⚠️ Gemini error: " msg))

(defn say-timeout [minutes]
  (str "⏰ timed out after " minutes " min — try a smaller ask?"))

(defn say-unconfigured []
  (str "🪨 grug brain not plugged in. need Gemini key + GitHub auth (App or token). grug wait 😴"))

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

(def ^:private workdir-prefix "yetibot-agent-")

(defn- delete-tree! [^java.io.File dir]
  (when (.exists dir)
    (doseq [f (reverse (file-seq dir))] (.delete f))))

(defn- work-dir
  "A unique scratch dir (under the system temp dir, i.e. /tmp) for one agent run,
   namespaced by the thread/target so concurrent runs in different threads never
   share a checkout. createTempDirectory guarantees uniqueness; the target tag
   just makes ownership obvious on disk."
  [target]
  (let [tag (-> (str target) (string/replace #"[^A-Za-z0-9_-]" ""))
        tag (subs tag 0 (min 40 (count tag)))]
    (.toFile (Files/createTempDirectory (str workdir-prefix tag "-")
                                        (make-array FileAttribute 0)))))

(defn sweep-stale-workdirs!
  "Best-effort cleanup of agent scratch dirs orphaned by a crash: delete any
   leftover under the temp dir older than `max-age-ms`. Each run also cleans its
   own dir in a finally; this is the safety net."
  [max-age-ms]
  (try
    (let [cutoff (- (System/currentTimeMillis) max-age-ms)
          tmp (io/file (System/getProperty "java.io.tmpdir"))]
      (doseq [^java.io.File d (or (.listFiles tmp) [])
              :when (and (.isDirectory d)
                         (string/starts-with? (.getName d) workdir-prefix)
                         (< (.lastModified d) cutoff))]
        (delete-tree! d)))
    (catch Exception e (debug "sweep-stale-workdirs! failed:" (.getMessage e)))))

(defn pr-urls
  "GitHub pull request URLs mentioned in text, de-duplicated."
  [text]
  (->> (re-seq #"https://github\.com/[\w.-]+/[\w.-]+/pull/\d+" (or text ""))
       distinct vec))

(defn parse-json-response
  "Pull the `response` field out of Gemini's --output-format json stdout,
   tolerating any leading non-JSON noise."
  [stdout]
  (let [grab #(-> (json/read-str % :key-fn keyword) :response)]
    (try (grab stdout)
         (catch Exception _
           (when-let [m (re-find #"(?s)\{.*\}" (or stdout ""))]
             (try (grab m) (catch Exception _ nil)))))))

;; ---------------------------------------------------------------------------
;; GitHub auth — enough to mint a token to hand Gemini as GH_TOKEN
;; ---------------------------------------------------------------------------

(def ^:private api-base "https://api.github.com")

(defn- gh-headers [auth]
  {"Authorization" auth
   "Accept" "application/vnd.github+json"
   "X-GitHub-Api-Version" "2022-11-28"})

(defn- gh-get [url auth]
  (client/get url {:headers (gh-headers auth) :as :json
                   :coerce :always :throw-exceptions false}))

(defn- gh-ok [{:keys [status body]} what]
  (if (<= 200 status 299)
    body
    (throw (ex-info (str what " failed: " (or (:message body) status))
                    {:status status :body body}))))

;; -- RS256 JWT (pure JDK; no BouncyCastle, to avoid classpath conflicts) ------

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- pem->der [pem]
  (-> pem
      (string/replace #"-----(BEGIN|END)[A-Z ]+-----" "")
      (string/replace #"\s" "")
      (->> (.decode (Base64/getDecoder)))))

(defn- der-tlv [tag ^bytes content]
  (let [n (count content)
        len (cond
              (< n 0x80) [n]
              (< n 0x100) [0x81 n]
              :else [0x82 (bit-shift-right n 8) (bit-and n 0xff)])]
    (byte-array (concat [tag] (map unchecked-byte len) content))))

(defn- pkcs1->pkcs8 [^bytes pkcs1]
  (let [version (byte-array [0x02 0x01 0x00])
        rsa-alg (byte-array (map unchecked-byte
                                 [0x30 0x0d 0x06 0x09 0x2a 0x86 0x48
                                  0x86 0xf7 0x0d 0x01 0x01 0x01 0x05 0x00]))]
    (der-tlv 0x30 (byte-array (concat version rsa-alg (der-tlv 0x04 pkcs1))))))

(defn- rsa-private-key [pem]
  (let [der (pem->der pem)
        der (if (string/includes? pem "BEGIN RSA PRIVATE KEY")
              (pkcs1->pkcs8 der)
              der)]
    (.generatePrivate (KeyFactory/getInstance "RSA")
                      (PKCS8EncodedKeySpec. der))))

(defn- rs256 [^String signing-input pem]
  (b64url (-> (doto (Signature/getInstance "SHA256withRSA")
                (.initSign (rsa-private-key pem))
                (.update (.getBytes signing-input "UTF-8")))
              (.sign))))

(defn app-jwt []
  (let [now (quot (System/currentTimeMillis) 1000)
        seg (fn [m] (b64url (.getBytes (json/write-str m) "UTF-8")))
        signing-input (str (seg {:alg "RS256" :typ "JWT"})
                           "."
                           (seg {:iat (- now 60) :exp (+ now (* 9 60)) :iss (app-id)}))]
    (str signing-input "." (rs256 signing-input (app-private-key)))))

(defn any-installation-id
  "An installation id for the App: the configured one, else its first install."
  [jwt-token]
  (or (config-str [:github :app :installation-id])
      (-> (gh-get (str api-base "/app/installations") (str "Bearer " jwt-token))
          (gh-ok "list app installations")
          first :id)))

(defn github-token
  "A token to hand Gemini as GH_TOKEN: a freshly-minted App installation token
   (scoped to everything the App can reach), or the static PAT."
  []
  (if (app-configured?)
    (let [jwt-token (app-jwt)]
      (-> (client/post (format "%s/app/installations/%s/access_tokens"
                               api-base (any-installation-id jwt-token))
                       {:headers (gh-headers (str "Bearer " jwt-token))
                        :as :json :coerce :always :throw-exceptions false})
          (gh-ok "GitHub App token exchange")
          :token))
    (github-pat)))

;; ---------------------------------------------------------------------------
;; The agent prompt — Gemini does everything via gh + git
;; ---------------------------------------------------------------------------

(defn build-agent-prompt [request context]
  (str "You are Yetibot's coding agent in a team chat, running non-interactively "
       "in an empty scratch directory. Your job is to RESPOND to the user's "
       "request — and, when it's warranted, turn it into a pull request or point "
       "at an existing relevant one.\n\n"
       "Tools (shell):\n"
       "- `gh`: the GitHub CLI, authenticated (GH_TOKEN is set). Use it to "
       "research — search repos/issues/PRs, read code, list existing pull "
       "requests — and to clone and open PRs.\n"
       "- `git`: for local changes.\n\n"
       "You have WRITE access to the organization's repositories. Always use "
       "HTTPS (clone with `gh repo clone <owner>/<repo>`) — never SSH, never fork. "
       "git is preconfigured to authenticate pushes with your token, so push "
       "branches straight to `origin`.\n\n"
       "Handling the request:\n"
       "- Research first with `gh`: which repo(s) are relevant, and is there "
       "already an open PR or issue addressing this? If so, link it rather than "
       "duplicating the work.\n"
       "- If a code change is warranted and none exists yet: pick the right repo "
       "(yetibot's chat commands live in `yetibot/core` under "
       "`src/yetibot/core/commands/` and load dynamically — a new command is "
       "usually just a new file there), clone over HTTPS, set git config "
       "user.name 'Yetibot' / user.email 'yetibot@yetibot.com', make a minimal "
       "focused change on a new branch, `git push -u origin <branch>`, and open a "
       "pull request with `gh pr create`.\n"
       "- If it's a question or discussion, just answer it, citing relevant "
       "code/PRs/issues.\n\n"
       "Do the work, then reply with ONLY your final answer to the request — no "
       "step-by-step narration in the reply. Keep it concise (a few sentences). "
       "Reference any relevant pull requests as full URLs "
       "(e.g. https://github.com/yetibot/core/pull/123), never the #123 shorthand.\n\n"
       (when-not (string/blank? context)
         (str "Conversation so far:\n" (string/trim context) "\n\n"))
       "Request:\n" (string/trim request)))

;; Authenticate git pushes to github.com with GH_TOKEN, so the agent's plain
;; `git push` over HTTPS works without prompting (the token alone only auths the
;; `gh` API, not git). The helper reads GH_TOKEN from the environment at runtime.
(def ^:private git-credential-helper
  "!f() { echo username=x-access-token; echo \"password=$GH_TOKEN\"; }; f")

(defn- kill-tree!
  "Forcibly kill a process and all of its descendants. Gemini spawns bun/git/gh
   children; killing only the parent would orphan them and pile up CPU load."
  [^Process proc]
  (let [descendants (doall (iterator-seq (.iterator (.descendants proc))))]
    (doseq [^java.lang.ProcessHandle h (cons (.toHandle proc) descendants)]
      (try (.destroyForcibly h) (catch Exception _ nil)))))

(defn run-gemini-agent
  "Run the Gemini CLI headlessly with structured JSON output (no intermediate
   narration on stdout; stderr is discarded). Returns
   {:response <final answer text or nil> :exit n :timed-out bool}."
  [workdir request context token]
  ;; cap the agent's turn budget via a workspace settings file
  (let [settings-dir (io/file workdir ".gemini")]
    (.mkdirs settings-dir)
    (spit (io/file settings-dir "settings.json")
          (json/write-str {:maxSessionTurns (agent-max-turns)})))
  (let [pb (doto (ProcessBuilder. [(cli-bin) "--yolo" "--output-format" "json"
                                   "--model" model
                                   "--prompt" (build-agent-prompt request context)])
             (.directory (io/file workdir))
             (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))]
    (doto (.environment pb)
      (.put "GEMINI_API_KEY" (gemini-key))
      (.put "GEMINI_CLI_TRUST_WORKSPACE" "true")
      (.put "GH_TOKEN" (or token ""))
      ;; inject git config via env (no global state): a credential helper that
      ;; authenticates HTTPS pushes with GH_TOKEN, plus insteadOf rewrites so any
      ;; SSH-style github remote is forced to HTTPS (where the token applies).
      (.put "GIT_CONFIG_COUNT" "3")
      (.put "GIT_CONFIG_KEY_0" "credential.https://github.com.helper")
      (.put "GIT_CONFIG_VALUE_0" git-credential-helper)
      (.put "GIT_CONFIG_KEY_1" "url.https://github.com/.insteadOf")
      (.put "GIT_CONFIG_VALUE_1" "git@github.com:")
      (.put "GIT_CONFIG_KEY_2" "url.https://github.com/.insteadOf")
      (.put "GIT_CONFIG_VALUE_2" "ssh://git@github.com/"))
    (info "running gemini agent" (cli-bin) "in" (str workdir))
    (let [proc (.start pb)
          timed-out (atom false)
          ;; hard wall-clock cap: kill the run if it overruns
          watchdog (future
                     (Thread/sleep (agent-timeout-ms))
                     (when (.isAlive proc)
                       (reset! timed-out true)
                       (kill-tree! proc)))
          stdout (slurp (.getInputStream proc))
          exit (.waitFor proc)]
      (future-cancel watchdog)
      {:response (redact (parse-json-response stdout))
       :exit exit
       :timed-out @timed-out})))

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

(defn- thread-context
  "Recent conversation in the channel/thread, oldest-first, as plain text."
  [channel-id]
  (try
    (->> @(discord/get-channel-messages! (rest-conn) channel-id :limit 25)
         reverse
         (map (fn [m] (str (get-in m [:author :username]) ": " (:content m))))
         (remove string/blank?)
         (string/join "\n"))
    (catch Exception e (debug "thread-context failed:" (.getMessage e)) "")))

;; ---------------------------------------------------------------------------
;; Command
;; ---------------------------------------------------------------------------

;; One agent run at a time: each spawns Gemini (bun/node) + git clones, which on
;; a small host easily over-subscribes the CPU. Excess invocations are turned
;; away rather than queued.
(defonce ^:private run-permit (java.util.concurrent.Semaphore. 1))

(defn say-busy []
  "🦴 grug already smashing one rock — wait til grug finish, then ask again 🪨")

(defn run-agent
  "Async body: mint a token, run Gemini headlessly, then delete the transient
   status message and post one clean final reply — Gemini's answer plus links to
   any relevant PRs. No intermediate narration."
  [{:keys [request target context-channel on-discord status-id]}]
  (binding [chat/*target* target]
    (sweep-stale-workdirs! (agent-workdir-max-age-ms))
    (let [dir (work-dir target)]
      (try
        (let [context (when on-discord (thread-context context-channel))
              token (github-token)
              {:keys [response exit timed-out]} (run-gemini-agent dir request context token)
              reply (cond
                      timed-out (say-timeout (quot (agent-timeout-ms) 60000))
                      (not (string/blank? response)) (say-final response (pr-urls response))
                      (pos? exit) (say-broken (str "exited " exit
                                                   " — no answer returned (a PR may still have been opened)"))
                      :else (say-final "done." nil))]
          (when (and on-discord status-id) (delete-msg! target status-id))
          (chat/send-msg reply))
        (catch Exception e
          (error "agent command failed" e)
          (when (and on-discord status-id) (delete-msg! target status-id))
          (chat/send-msg (say-broken (.getMessage e))))
        (finally
          (try (delete-tree! dir)
               (catch Exception e (warn "cleanup failed" (str dir) e))))))))

(defn agent-cmd
  "agent <prompt> # hand the request to Gemini (gh+git) and reply with its answer"
  {:yb/cat #{:util}}
  [{[_ request] :match chat-source :chat-source}]
  (cond
    (not (configured?)) (say-unconfigured)
    (not (.tryAcquire run-permit)) (say-busy)
    :else
    (let [adapter chat/*adapter*
          {:keys [raw-event]} chat-source
          channel (or (:channel-id raw-event) chat/*target*)
          msg-id (:id raw-event)
          on-discord (discord?)
          ;; on Discord, work inside a thread off the triggering message
          target (if (and on-discord channel msg-id)
                   (start-thread! channel msg-id request)
                   chat/*target*)
          ;; transient status; deleted when the final answer is posted
          status-id (:id (binding [chat/*target* target] (chat/send-msg (say-working))))]
      (future
        (try
          (binding [chat/*adapter* adapter]
            (run-agent {:request request :target target :context-channel channel
                        :on-discord on-discord :status-id status-id}))
          (finally (.release run-permit))))
      ;; the answer is posted out of band; suppress the framework's reply
      (chat/suppress {}))))

;; Register only when Gemini + GitHub auth are configured.
(when (configured?)
  (cmd-hook #"agent"
            #"(?s)(.+)" agent-cmd))
