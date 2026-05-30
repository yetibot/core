(ns yetibot.core.commands.agent
  "A meme-loving coding agent. `agent <prompt>` figures out which GitHub repos
   the request touches, clones each, drives the Gemini CLI to make the change,
   and opens a pull request per repo — narrating progress in a chat thread with
   a quirky grug/caveman persona.

   On Discord the agent works inside a thread spun off the triggering message,
   so a team can keep replying in that thread and re-trigger `agent` to iterate;
   the whole thread is fed back as context each time. On other adapters it
   degrades gracefully to plain in-channel replies.

   GitHub auth is either a static token (PAT) or a GitHub App, whichever is
   configured — an App is preferred since it mints short-lived, repo-scoped
   installation tokens instead of relying on a long-lived personal token."
  (:require
   [clojure.java.shell :as shell]
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
;;   YB_GEMINI_DEFAULT_ORG  - org used for repo discovery / bare repo names
;;
;; GitHub auth, in order of preference:
;;
;;   GitHub App (short-lived, repo-scoped installation tokens):
;;     YB_GITHUB_APP_ID              - the App's ID
;;     YB_GITHUB_APP_PRIVATE_KEY     - the App's PEM private key
;;     YB_GITHUB_APP_INSTALLATION_ID - optional; auto-resolved per repo if unset
;;
;;   Static token:
;;     YB_GITHUB_TOKEN               - a personal access token
;; ---------------------------------------------------------------------------

(s/def ::str string?)
(s/def ::id (s/or :string string? :number number?))

(defn- config-str [path]
  (:value (get-config ::str path)))

;; The strongest Gemini coding model.
(def model "gemini-2.5-pro")

(defn gemini-key [] (config-str [:gemini :key]))
(defn cli-bin [] (or (config-str [:gemini :cli]) "gemini"))
(defn default-org [] (or (config-str [:gemini :default-org]) "yetibot"))

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
;; Persona — grug/caveman/meme voice for the agent's chat messages only. The
;; code Gemini writes stays clean; this just flavors what the bot says.
;; ---------------------------------------------------------------------------

(defn say-thinking [prompt]
  (str "🦴 grug see request: _" (string/trim prompt) "_\n"
       "grug sharpen rock, find repos... 🧠⏳"))

(defn say-plan [repos]
  (str "🪓 grug smash these repos: " (string/join ", " (map #(str "`" % "`") repos))
       ". much code, very PR. 🚀"))

(defn say-done [results]
  (let [ok (filter :url results)
        bad (remove :url results)]
    (str "✅ grug done! "
         (if (seq ok) "much wow 🎉 stonks 📈\n" "but grug make no fire this time 🔥\n")
         (string/join "\n"
                      (for [{:keys [repo url error]} results]
                        (if url
                          (str "• `" repo "` → " url)
                          (str "• `" repo "` → 💥 " error))))
         (when (and (seq ok) (seq bad)) "\ngrug do best. some rock too hard. 🪨"))))

(defn say-nothing [repos]
  (str "🤔 grug look at " (string/join ", " (map #(str "`" %"`") repos))
       " but see no change to make. tell grug clearer, grug try again. 🦴"))

(defn say-broken [msg]
  (str "💥 grug hit big rock: " msg "\ngrug sad. 😵 try again maybe?"))

(defn say-unconfigured []
  (str "🪨 grug brain not plugged in. need Gemini key + GitHub auth (App or token). grug wait. 😴"))

(defn say-no-repos []
  (str "🤷 grug no can tell which repo to smash. say it like `agent yetibot/core <change>`. 🦴"))

;; ---------------------------------------------------------------------------
;; Shell helpers
;; ---------------------------------------------------------------------------

(defn sh*
  "Run a shell command, optionally in :dir with extra :env vars merged on top of
   the current environment. Returns the clojure.java.shell result map."
  [{:keys [dir env]} & args]
  (let [full-env (when (seq env)
                   (merge (into {} (System/getenv)) env))
        opts (cond-> []
               dir (conj :dir dir)
               full-env (conj :env full-env))
        result (apply shell/sh (concat args opts))]
    (debug "sh" (pr-str (vec args)) "exit" (:exit result))
    result))

(defn redact
  "Strip embedded credentials (e.g. a token in a clone URL) from a string so
   they never reach chat output or logs."
  [s]
  (when s
    (-> s
        (string/replace #"(://[^:/@\s]+:)[^@\s]+(@)" "$1***$2")
        (string/replace #"(://)[^:/@\s]+(@)" "$1***$2"))))

(defn check-sh
  "Like sh* but throws ex-info with captured (credential-redacted) output when
   the command fails."
  [ctx & args]
  (let [{:keys [exit out err] :as result} (apply sh* ctx args)]
    (when-not (zero? exit)
      (throw (ex-info (redact (format "command failed (exit %s): %s\n%s"
                                      exit (string/join " " args)
                                      (or (not-empty (string/trim (str err)))
                                          (string/trim (str out)))))
                      {:exit exit
                       :out (redact out)
                       :err (redact err)
                       :args (mapv redact args)})))
    result))

;; ---------------------------------------------------------------------------
;; Pure-ish helpers
;; ---------------------------------------------------------------------------

(defn parse-repo
  "Parse `owner/repo` or bare `repo` (using the default org) into [owner repo]."
  [repo-arg]
  (let [parts (-> repo-arg string/trim (string/split #"/"))]
    (if (>= (count parts) 2)
      [(first parts) (second parts)]
      [(default-org) (first parts)])))

(defn authed-clone-url [token owner repo]
  (format "https://x-access-token:%s@github.com/%s/%s.git" token owner repo))

(defn branch-name []
  (str "yetibot/agent-" (System/currentTimeMillis)))

(defn pr-title [instruction]
  (let [one-line (-> instruction string/trim (string/replace #"\s+" " "))
        capped (if (> (count one-line) 72) (str (subs one-line 0 69) "...") one-line)]
    (if (seq capped)
      (str (string/upper-case (subs capped 0 1)) (subs capped 1))
      "Yetibot change")))

(defn pr-body [instruction gemini-out]
  (str "Requested via Yetibot:\n\n> " (string/trim instruction) "\n\n"
       "This change was authored by the Gemini CLI (`" model "`) "
       "and opened automatically by Yetibot 🤖.\n\n"
       (when-not (string/blank? gemini-out)
         (str "<details><summary>Gemini output</summary>\n\n```\n"
              (string/trim gemini-out)
              "\n```\n\n</details>\n"))))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "yetibot-agent" (make-array FileAttribute 0))))

(defn default-branch [repo-dir]
  (let [{:keys [exit out]} (sh* {:dir repo-dir}
                                "git" "rev-parse" "--abbrev-ref" "origin/HEAD")]
    (if (zero? exit)
      (-> out string/trim (string/replace #"^origin/" ""))
      "master")))

(defn working-tree-dirty? [repo-dir]
  (-> (sh* {:dir repo-dir} "git" "status" "--porcelain") :out string/blank? not))

;; ---------------------------------------------------------------------------
;; Gemini
;; ---------------------------------------------------------------------------

(defn build-gemini-prompt
  "Wrap the user's instruction with guidance so the CLI edits files directly.
   Optional thread context gives the agent the prior conversation to work from."
  [instruction context]
  (str "You are an automated coding agent working in a cloned git repository. "
       "Make the following change directly to the files in this repository, "
       "keeping the change minimal and focused.\n\n"
       "The `gh` CLI is installed and authenticated (GH_TOKEN is set) — use it "
       "freely for read-only GitHub operations that help you make a correct "
       "change: reading issues and PRs, code search, viewing checks/CI, repo "
       "metadata, etc. Do NOT commit, push, or open a pull request yourself "
       "(neither with git nor `gh`) — only edit files; Yetibot handles the PR. "
       "When you are done, briefly summarize what you changed.\n\n"
       (when-not (string/blank? context)
         (str "Conversation context so far:\n" (string/trim context) "\n\n"))
       "Change to make:\n" (string/trim instruction)))

(defn run-gemini
  "Invoke the Gemini CLI in repo-dir to perform the coding instruction. Trusts
   the workspace explicitly so it runs non-interactively, and exports GH_TOKEN
   so the agent can use the authenticated `gh` CLI for read-only GitHub lookups."
  [repo-dir instruction context token]
  (info "running gemini" (cli-bin) "model" model)
  (check-sh {:dir repo-dir
             :env {"GEMINI_API_KEY" (gemini-key)
                   "GEMINI_CLI_TRUST_WORKSPACE" "true"
                   "GH_TOKEN" (or token "")}}
            (cli-bin)
            "--model" model
            "--yolo"
            "--prompt" (build-gemini-prompt instruction context)))

(defn gemini-text
  "One-shot Gemini text completion via the REST API. Used for lightweight
   reasoning (e.g. picking repos). Returns the model's text, or nil on failure."
  [prompt]
  (try
    (let [{:keys [status body]}
          (client/post
           (format "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"
                   model (gemini-key))
           {:content-type :json :as :json :coerce :always :throw-exceptions false
            :body (json/write-str {:contents [{:parts [{:text prompt}]}]})})]
      (when (<= 200 status 299)
        (-> body :candidates first :content :parts first :text)))
    (catch Exception e
      (warn "gemini-text failed:" (.getMessage e))
      nil)))

;; ---------------------------------------------------------------------------
;; GitHub
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

(defn installation-id [jwt-token owner repo]
  (or (config-str [:github :app :installation-id])
      (-> (gh-get (format "%s/repos/%s/%s/installation" api-base owner repo)
                  (str "Bearer " jwt-token))
          (gh-ok "GitHub App installation lookup")
          :id)))

(defn installation-token [owner repo]
  (let [jwt-token (app-jwt)
        id (installation-id jwt-token owner repo)]
    (-> (client/post (format "%s/app/installations/%s/access_tokens" api-base id)
                     {:headers (gh-headers (str "Bearer " jwt-token))
                      :as :json :coerce :always :throw-exceptions false})
        (gh-ok "GitHub App token exchange")
        :token)))

(defn github-token
  "Resolve a GitHub token for owner/repo: a freshly-minted App installation
   token when an App is configured, otherwise the static PAT."
  [owner repo]
  (if (app-configured?)
    (installation-token owner repo)
    (github-pat)))

(defn list-org-repos
  "Names (owner/repo) of the org's repos, for repo discovery. Best-effort."
  [org]
  (try
    (let [auth (if (app-configured?)
                 (str "Bearer " (app-jwt))
                 (str "Bearer " (github-pat)))]
      (->> (gh-get (format "%s/orgs/%s/repos?per_page=100&type=all" api-base org) auth)
           (#(gh-ok % "list org repos"))
           (map :full_name)
           (remove nil?)))
    (catch Exception e
      (warn "list-org-repos failed:" (.getMessage e))
      [])))

(defn create-pull-request [token owner repo {:keys [title body head base]}]
  (let [{:keys [status body]}
        (client/post (format "%s/repos/%s/%s/pulls" api-base owner repo)
                     {:headers (gh-headers (str "Bearer " token))
                      :content-type :json :as :json :coerce :always
                      :throw-exceptions false
                      :body (json/write-str {:title title :body body
                                             :head head :base base})})]
    (debug "create-pull-request status" status)
    (if (<= 200 status 299)
      body
      (throw (ex-info (str "GitHub PR creation failed: " (or (:message body) status))
                      {:status status :body body})))))

;; ---------------------------------------------------------------------------
;; Repo discovery
;; ---------------------------------------------------------------------------

(defn explicit-repos
  "owner/repo tokens written literally in the prompt."
  [prompt]
  (->> (re-seq #"\b[\w.-]+/[\w.-]+\b" prompt) distinct vec))

(defn discover-repos
  "Decide which repos the request touches: explicit owner/repo tokens if present,
   otherwise ask Gemini to pick from the org's repo list."
  [prompt]
  (let [explicit (explicit-repos prompt)]
    (if (seq explicit)
      explicit
      (let [repos (list-org-repos (default-org))]
        (if (empty? repos)
          []
          (let [answer (gemini-text
                        (str "From this list of GitHub repos:\n"
                             (string/join "\n" repos)
                             "\n\nWhich repos must be changed to fulfill this request? "
                             "Reply with ONLY a comma-separated list of owner/repo "
                             "from the list above, nothing else.\n\nRequest: " prompt))]
            (->> (string/split (or answer "") #"[,\n]")
                 (map string/trim)
                 (filter (set repos))
                 distinct vec)))))))

;; ---------------------------------------------------------------------------
;; Orchestration
;; ---------------------------------------------------------------------------

(defn file-pr
  "End to end for one repo: clone, rebase on trunk, run Gemini, commit, push,
   open PR. Returns the GitHub PR map (with :html_url) or throws."
  [{:keys [owner repo instruction context token]}]
  (let [dir (temp-dir)
        repo-dir dir
        ctx {:dir repo-dir}
        clone-url (authed-clone-url token owner repo)
        branch (branch-name)]
    (try
      (info "cloning" (format "%s/%s" owner repo) "into" (str dir))
      (check-sh {} "git" "clone" "--depth" "50" clone-url (str repo-dir))
      (check-sh ctx "git" "config" "user.name" "Yetibot")
      (check-sh ctx "git" "config" "user.email" "yetibot@yetibot.com")
      (let [trunk (default-branch repo-dir)]
        (info "trunk is" trunk)
        (check-sh ctx "git" "checkout" trunk)
        (check-sh ctx "git" "fetch" "origin" trunk)
        (check-sh ctx "git" "pull" "--rebase" "origin" trunk)
        (check-sh ctx "git" "checkout" "-b" branch)
        (let [gemini-result (run-gemini repo-dir instruction context token)]
          (when-not (working-tree-dirty? repo-dir)
            (throw (ex-info "Gemini did not make any changes" {:type :no-changes})))
          (check-sh ctx "git" "add" "-A")
          (check-sh ctx "git" "commit" "-m" (pr-title instruction))
          (check-sh ctx "git" "push" "-u" "origin" branch)
          (create-pull-request token owner repo
                               {:title (pr-title instruction)
                                :body (pr-body instruction (:out gemini-result))
                                :head branch
                                :base trunk})))
      (finally
        (try (sh* {} "rm" "-rf" (str dir))
             (catch Exception e (warn "failed to clean up" (str dir) e)))))))

(defn run-repo
  "file-pr for one `owner/repo`, captured into a result map (never throws)."
  [repo-full instruction context]
  (let [[owner repo] (parse-repo repo-full)
        full (str owner "/" repo)]
    (try
      (let [token (github-token owner repo)
            {:keys [html_url]} (file-pr {:owner owner :repo repo
                                         :instruction instruction
                                         :context context :token token})]
        {:repo full :url html_url})
      (catch clojure.lang.ExceptionInfo e
        (if (= :no-changes (:type (ex-data e)))
          {:repo full :no-changes true}
          (do (error "agent: failed for" full e)
              {:repo full :error (.getMessage e)})))
      (catch Exception e
        (error "agent: failed for" full e)
        {:repo full :error (.getMessage e)}))))

;; ---------------------------------------------------------------------------
;; Discord thread plumbing (guarded; degrades to plain replies elsewhere)
;; ---------------------------------------------------------------------------

(defn- discord? []
  (and chat/*adapter*
       (= "discord" (some-> (a/platform-name chat/*adapter*) string/lower-case))))

(defn- rest-conn [] (:rest @(:conn chat/*adapter*)))

(defn- start-thread!
  "Spin a Discord thread off the triggering message; returns the thread channel
   id, or the original channel id if threading isn't possible (e.g. already in a
   thread)."
  [channel-id message-id title]
  (or (try
        (:id @(discord/start-thread-with-message!
               (rest-conn) channel-id message-id (subs title 0 (min 90 (count title))) 1440))
        (catch Exception e (debug "start-thread! fell back:" (.getMessage e)) nil))
      channel-id))

(defn- delete-msg! [channel-id message-id]
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

(defn run-agent
  "The async body: figure out repos, run them, narrate progress in `target`.
   `ack-id` (if any) is the thinking message to delete before the final reply."
  [{:keys [prompt target ack-id discord?]}]
  (binding [chat/*target* target]
    (try
      (let [context (when discord? (thread-context target))
            repos (discover-repos prompt)]
        (if (empty? repos)
          (chat/send-msg (say-no-repos))
          (do
            (chat/send-msg (say-plan repos))
            (let [results (mapv #(run-repo % prompt context) repos)]
              (when (and discord? ack-id) (delete-msg! target ack-id))
              (cond
                (some :url results) (chat/send-msg (say-done results))
                (every? :no-changes results) (chat/send-msg (say-nothing repos))
                :else (chat/send-msg (say-done results)))))))
      (catch Exception e
        (error "agent command failed" e)
        (when (and discord? ack-id) (delete-msg! target ack-id))
        (chat/send-msg (say-broken (.getMessage e)))))))

(defn agent-cmd
  "agent <prompt> # grug figures out the repos, makes the changes, opens PRs"
  {:yb/cat #{:util}}
  [{[_ prompt] :match chat-source :chat-source}]
  (if-not (configured?)
    (say-unconfigured)
    (let [adapter chat/*adapter*
          {:keys [raw-event]} chat-source
          channel (or (:channel-id raw-event) chat/*target*)
          msg-id (:id raw-event)
          on-discord (discord?)
          ;; on Discord, work inside a thread off the triggering message
          target (if (and on-discord channel msg-id)
                   (start-thread! channel msg-id (pr-title prompt))
                   chat/*target*)
          ack-id (binding [chat/*target* target]
                   (:id (chat/send-msg (say-thinking prompt))))]
      ;; do the slow work off-thread; messages post out of band into `target`
      (future
        (binding [chat/*adapter* adapter]
          (run-agent {:prompt prompt :target target
                      :ack-id ack-id :discord? on-discord})))
      nil)))

;; Register only when Gemini + GitHub auth are configured.
(when (configured?)
  (cmd-hook #"agent"
            #"(?s)(.+)" agent-cmd))
