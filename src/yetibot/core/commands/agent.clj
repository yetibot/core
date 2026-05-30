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

(defn say-thinking [prompt]
  (str "🦴 grug get request: _" (string/trim prompt) "_\n"
       "grug wake gemini brain 🧠⚡ — grug narrate as grug work..."))

(defn say-progress [chunk]
  (str "🧠 " (str "```\n" (string/trim chunk) "\n```")))

(defn say-done [pr-urls]
  (if (seq pr-urls)
    (str "✅ grug done! much PR, very wow 🎉 stonks 📈\n"
         (string/join "\n" (map #(str "• " %) pr-urls)))
    (str "✅ grug done! 🦴 (no PR this time — maybe grug just answer above)")))

(defn say-broken [msg]
  (str "💥 grug hit big rock: " msg "\ngrug sad 😵 — see log above maybe."))

(defn say-timeout [minutes]
  (str "⏰ grug run out of time (" minutes " min) and stop. "
       "too big rock for grug — try smaller ask? 🦴"))

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
  (str "You are Yetibot's autonomous coding agent, running non-interactively in "
       "an empty scratch directory. You have a shell with two key tools:\n"
       "- `gh`: the GitHub CLI, already authenticated (GH_TOKEN is set). Use it "
       "for everything GitHub — finding repos (`gh search repos`, `gh repo list`), "
       "reading code/issues/PRs, cloning (`gh repo clone`), and opening pull "
       "requests (`gh pr create`).\n"
       "- `git`: for local changes.\n\n"
       "You have WRITE access to the organization's repositories. Always use "
       "HTTPS (clone with `gh repo clone <owner>/<repo>` or "
       "`git clone https://github.com/<owner>/<repo>.git`) — do NOT use SSH and "
       "do NOT fork. git is preconfigured to authenticate pushes to github.com "
       "with your token, so push your branch straight to `origin`.\n\n"
       "Fulfill the user's request end to end:\n"
       "1. Work out which repo(s) are involved (use gh to search if unsure). "
       "Note: yetibot's chat commands live in the `yetibot/core` repo under "
       "`src/yetibot/core/commands/` and load dynamically, so a new command is "
       "usually just a new file there.\n"
       "2. Clone what you need into the current directory over HTTPS.\n"
       "3. Before committing, set: git config user.name 'Yetibot' and "
       "git config user.email 'yetibot@yetibot.com'.\n"
       "4. Make the change on a new branch, keeping it minimal and focused.\n"
       "5. `git push -u origin <branch>` then open a pull request with "
       "`gh pr create` against the default branch. Output the PR URL.\n"
       "6. If the request is just a question (no code change needed), answer it "
       "concisely and do NOT open a PR.\n\n"
       "Narrate what you're doing in short steps as you go. End with a brief "
       "summary including any pull request URLs.\n\n"
       (when-not (string/blank? context)
         (str "Conversation so far:\n" (string/trim context) "\n\n"))
       "Request:\n" (string/trim request)))

;; Authenticate git pushes to github.com with GH_TOKEN, so the agent's plain
;; `git push` over HTTPS works without prompting (the token alone only auths the
;; `gh` API, not git). The helper reads GH_TOKEN from the environment at runtime.
(def ^:private git-credential-helper
  "!f() { echo username=x-access-token; echo \"password=$GH_TOKEN\"; }; f")

(defn- noise-line?
  "Gemini CLI startup chatter that's not worth relaying to chat."
  [line]
  (boolean (re-find #"YOLO mode is enabled|256-color|Ripgrep is not available|\[STARTUP\]|Approval mode overridden"
                    line)))

(defn run-gemini-agent
  "Run the Gemini CLI as an autonomous agent in `workdir`, streaming combined
   stdout/stderr line-by-line to `on-chunk` (called with redacted text chunks).
   Returns {:exit n :out <full output>}."
  [workdir request context token on-chunk]
  ;; cap the agent's turn budget via a workspace settings file
  (let [settings-dir (io/file workdir ".gemini")]
    (.mkdirs settings-dir)
    (spit (io/file settings-dir "settings.json")
          (json/write-str {:maxSessionTurns (agent-max-turns)})))
  (let [pb (doto (ProcessBuilder. [(cli-bin) "--yolo" "--model" model
                                   "--prompt" (build-agent-prompt request context)])
             (.directory (io/file workdir))
             (.redirectErrorStream true))]
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
                       (.destroyForcibly proc)))
          full (StringBuilder.)]
      (with-open [rdr (io/reader (.getInputStream proc))]
        (loop [buf (StringBuilder.)]
          (let [line (.readLine rdr)]
            (cond
              (nil? line)
              (when (pos? (.length buf)) (on-chunk (redact (str buf))))

              (noise-line? line)
              (recur buf)

              (>= (.length buf) 1200)
              (do (on-chunk (redact (str buf)))
                  (.append full line) (.append full "\n")
                  (recur (doto (StringBuilder.) (.append line) (.append "\n"))))

              :else
              (do (.append full line) (.append full "\n")
                  (.append buf line) (.append buf "\n")
                  (recur buf))))))
      (let [exit (.waitFor proc)]
        (future-cancel watchdog)
        {:exit exit :out (str full) :timed-out @timed-out}))))

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

(defn- edit-msg!
  "Edit a Discord message in place (best-effort)."
  [channel-id message-id content]
  (try @(discord/edit-message! (rest-conn) channel-id message-id :content content)
       (catch Exception e (debug "edit-msg! failed:" (.getMessage e)))))

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

(def ^:private max-progress-msgs 20)

(def ^:private status-tail-chars 1500)
(def ^:private edit-throttle-ms 2000)

(defn run-agent
  "Async body: mint a token, run Gemini as an autonomous agent in a scratch dir,
   and report progress. On Discord, fold all progress into the single status
   message (`status-id`) via throttled in-place edits showing a rolling tail, so
   the thread stays tidy; elsewhere, post a capped number of chunks. Finishes by
   replacing the status with a summary including any PR links."
  [{:keys [request target context-channel on-discord status-id]}]
  (binding [chat/*target* target]
    (sweep-stale-workdirs! (agent-workdir-max-age-ms))
    (let [dir (work-dir target)
          live-edit? (and on-discord status-id)
          tail (atom "")
          last-edit (atom 0)
          posted (atom 0)
          relay (fn [chunk]
                  (when-not (string/blank? chunk)
                    (let [t (swap! tail #(let [s (str % "\n" chunk)]
                                           (subs s (max 0 (- (count s) status-tail-chars)))))
                          now (System/currentTimeMillis)]
                      (cond
                        live-edit?
                        (when (> (- now @last-edit) edit-throttle-ms)
                          (reset! last-edit now)
                          (edit-msg! target status-id (say-progress t)))

                        (< @posted max-progress-msgs)
                        (do (swap! posted inc) (chat/send-msg (say-progress chunk)))))))
          finish (fn [content]
                   (if live-edit?
                     (edit-msg! target status-id content)
                     (chat/send-msg content)))]
      (try
        (let [context (when on-discord (thread-context context-channel))
              token (github-token)
              {:keys [exit out timed-out]} (run-gemini-agent dir request context token relay)
              urls (pr-urls out)]
          (finish
           (cond
             ;; a PR means success regardless of exit — Gemini sometimes errors
             ;; its final stream ("Invalid stream") after the work is done
             (seq urls) (say-done urls)
             timed-out (say-timeout (quot (agent-timeout-ms) 60000))
             (pos? exit) (say-broken (str "gemini exited " exit))
             :else (say-done []))))
        (catch Exception e
          (error "agent command failed" e)
          (finish (say-broken (.getMessage e))))
        (finally
          (try (delete-tree! dir)
               (catch Exception e (warn "cleanup failed" (str dir) e))))))))

(defn agent-cmd
  "agent <prompt> # grug hands it to Gemini, which uses gh+git to make a PR"
  {:yb/cat #{:util}}
  [{[_ request] :match chat-source :chat-source}]
  (if-not (configured?)
    (say-unconfigured)
    (let [adapter chat/*adapter*
          {:keys [raw-event]} chat-source
          channel (or (:channel-id raw-event) chat/*target*)
          msg-id (:id raw-event)
          on-discord (discord?)
          ;; on Discord, work inside a thread off the triggering message
          target (if (and on-discord channel msg-id)
                   (start-thread! channel msg-id request)
                   chat/*target*)]
      ;; post the status message now; on Discord we edit it in place with progress
      (let [status-id (:id (binding [chat/*target* target]
                             (chat/send-msg (say-thinking request))))]
        (future
          (binding [chat/*adapter* adapter]
            (run-agent {:request request :target target :context-channel channel
                        :on-discord on-discord :status-id status-id}))))
      ;; everything is narrated out of band; suppress the framework's reply so it
      ;; doesn't post "No results" into the parent channel.
      (chat/suppress {}))))

;; Register only when Gemini + GitHub auth are configured.
(when (configured?)
  (cmd-hook #"agent"
            #"(?s)(.+)" agent-cmd))
