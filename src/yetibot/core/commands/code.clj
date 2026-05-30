(ns yetibot.core.commands.code
  "Use Gemini to make a code change in a GitHub repo and open a pull request.

   Example:

     code yetibot/core increase banana budget

   This clones the repo using Yetibot's GitHub credentials, checks out and
   rebases on the latest trunk, runs the Gemini CLI to perform the requested
   change, then commits, pushes a branch, and opens a PR back to the repo.

   GitHub auth is either a static token (PAT) or a GitHub App, whichever is
   configured — an App is preferred since it mints short-lived, repo-scoped
   installation tokens instead of relying on a long-lived personal token."
  (:require
   [clojure.java.shell :as shell]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [taoensso.timbre :refer [debug info warn error]]
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
;; Gemini config lives under [:gemini], sharing a single API key with the rest
;; of Yetibot's Gemini features. At minimum a :key (Gemini API key) is required.
;; Optionally override the CLI binary and the default org used when a repo is
;; given without an owner.
;;
;;   YB_GEMINI_KEY          - Gemini API key (required)
;;   YB_GEMINI_CLI          - path to the gemini CLI binary (default "gemini")
;;   YB_GEMINI_DEFAULT_ORG  - org used when repo given without an owner
;;
;; GitHub auth, in order of preference:
;;
;;   GitHub App (short-lived, repo-scoped installation tokens):
;;     YB_GITHUB_APP_ID              - the App's ID
;;     YB_GITHUB_APP_PRIVATE_KEY     - the App's PEM private key
;;     YB_GITHUB_APP_INSTALLATION_ID - optional; auto-resolved per repo if unset
;;
;;   Static token (reused from the yetibot/github plugin's [:github :token]):
;;     YB_GITHUB_TOKEN               - a personal access token
;; ---------------------------------------------------------------------------

;; generic spec for fetching single string values out of arbitrary config paths
(s/def ::str string?)
;; an App id may be configured as either a string or a number
(s/def ::id (s/or :string string? :number number?))

(defn- config-str [path]
  (:value (get-config ::str path)))

;; The strongest Gemini coding model.
(def model "gemini-2.5-pro")

(defn gemini-key [] (config-str [:gemini :key]))
(defn cli-bin [] (or (config-str [:gemini :cli]) "gemini"))
(defn default-org [] (or (config-str [:gemini :default-org]) "yetibot"))

(defn github-pat
  "Yetibot's static GitHub token (PAT), if configured."
  []
  (config-str [:github :token]))

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
  "The command is only available when Gemini and some GitHub auth are set."
  []
  (boolean (and (not (string/blank? (gemini-key)))
                (github-auth-configured?))))

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
  "Strip embedded credentials (e.g. the GitHub token in a clone URL) from a
   string so they never end up in chat output or logs."
  [s]
  (when s
    (-> s
        ;; redact the password in scheme://user:password@ URLs (e.g. the
        ;; GitHub token in https://x-access-token:TOKEN@github.com/...). This is
        ;; idempotent: re-running over an already-redacted "user:***@" is a noop.
        (string/replace #"(://[^:/@\s]+:)[^@\s]+(@)" "$1***$2")
        ;; redact bare userinfo tokens with no password, e.g. https://TOKEN@host
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

(defn authed-clone-url
  "HTTPS clone URL that embeds the token for push/pull auth."
  [token owner repo]
  (format "https://x-access-token:%s@github.com/%s/%s.git" token owner repo))

(defn branch-name
  "A unique branch name for the change."
  []
  (str "yetibot/code-" (System/currentTimeMillis)))

(defn pr-title
  "Derive a concise PR title from the instruction."
  [instruction]
  (let [trimmed (string/trim instruction)
        one-line (-> trimmed (string/replace #"\s+" " "))
        capped (if (> (count one-line) 72)
                 (str (subs one-line 0 69) "...")
                 one-line)]
    (if (seq capped)
      (str (string/upper-case (subs capped 0 1)) (subs capped 1))
      "Yetibot change")))

(defn pr-body
  [instruction gemini-out]
  (str "Requested via Yetibot:\n\n> " (string/trim instruction) "\n\n"
       "This change was authored by the Gemini CLI (`" model "`) "
       "and opened automatically by Yetibot 🤖.\n\n"
       (when-not (string/blank? gemini-out)
         (str "<details><summary>Gemini output</summary>\n\n```\n"
              (string/trim gemini-out)
              "\n```\n\n</details>\n"))))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "yetibot-code" (make-array FileAttribute 0))))

(defn default-branch
  "Detect the repo's default branch (trunk) from origin/HEAD."
  [repo-dir]
  (let [{:keys [exit out]} (sh* {:dir repo-dir}
                                "git" "rev-parse" "--abbrev-ref" "origin/HEAD")]
    (if (zero? exit)
      (-> out string/trim (string/replace #"^origin/" ""))
      "master")))

(defn working-tree-dirty?
  "Whether Gemini actually changed anything in the repo."
  [repo-dir]
  (-> (sh* {:dir repo-dir} "git" "status" "--porcelain")
      :out
      string/blank?
      not))

;; ---------------------------------------------------------------------------
;; Gemini
;; ---------------------------------------------------------------------------

(defn build-gemini-prompt
  "Wrap the user's instruction with guidance so the CLI edits files directly."
  [instruction]
  (str "You are an automated coding agent working in a cloned git repository. "
       "Make the following change directly to the files in this repository, "
       "keeping the change minimal and focused. Do not commit, push, or open a "
       "pull request yourself — only edit files. When you are done, briefly "
       "summarize what you changed.\n\n"
       "Change to make:\n" (string/trim instruction)))

(defn run-gemini
  "Invoke the Gemini CLI in repo-dir to perform the coding instruction. Uses the
   best configured coding model and auto-approves tool calls so it can edit
   files non-interactively."
  [repo-dir instruction]
  (info "running gemini" (cli-bin) "model" model)
  (check-sh {:dir repo-dir
             :env {"GEMINI_API_KEY" (gemini-key)}}
            (cli-bin)
            "--model" model
            "--yolo"
            "--prompt" (build-gemini-prompt instruction)))

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

(defn- gh-ok
  "Return the response body on 2xx, otherwise throw with the GitHub error."
  [{:keys [status body]} what]
  (if (<= 200 status 299)
    body
    (throw (ex-info (str what " failed: " (or (:message body) status))
                    {:status status :body body}))))

;; -- RS256 JWT (pure JDK; no BouncyCastle, to avoid classpath conflicts) ------

(defn- b64url
  "Base64url-encode bytes without padding, per the JWT spec."
  [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- pem->der
  "Strip PEM armor and base64-decode the body to DER bytes."
  [pem]
  (-> pem
      (string/replace #"-----(BEGIN|END)[A-Z ]+-----" "")
      (string/replace #"\s" "")
      (->> (.decode (Base64/getDecoder)))))

(defn- der-tlv
  "Build an ASN.1 tag-length-value with correctly encoded definite length."
  [tag ^bytes content]
  (let [n (count content)
        len (cond
              (< n 0x80) [n]
              (< n 0x100) [0x81 n]
              :else [0x82 (bit-shift-right n 8) (bit-and n 0xff)])]
    (byte-array (concat [tag] (map unchecked-byte len) content))))

(defn- pkcs1->pkcs8
  "Wrap a PKCS#1 RSAPrivateKey (GitHub's default key format) in a PKCS#8
   PrivateKeyInfo so the JDK KeyFactory can read it."
  [^bytes pkcs1]
  (let [version (byte-array [0x02 0x01 0x00])
        rsa-alg (byte-array (map unchecked-byte
                                 [0x30 0x0d 0x06 0x09 0x2a 0x86 0x48
                                  0x86 0xf7 0x0d 0x01 0x01 0x01 0x05 0x00]))]
    (der-tlv 0x30 (byte-array (concat version rsa-alg (der-tlv 0x04 pkcs1))))))

(defn- rsa-private-key
  "Load an RSA private key from a PEM string in either PKCS#8 or PKCS#1 form."
  [pem]
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

(defn app-jwt
  "A short-lived (≤10m) RS256 JWT identifying the GitHub App, per GitHub's spec.
   `iat` is backdated 60s to tolerate clock skew."
  []
  (let [now (quot (System/currentTimeMillis) 1000)
        seg (fn [m] (b64url (.getBytes (json/write-str m) "UTF-8")))
        signing-input (str (seg {:alg "RS256" :typ "JWT"})
                           "."
                           (seg {:iat (- now 60) :exp (+ now (* 9 60)) :iss (app-id)}))]
    (str signing-input "." (rs256 signing-input (app-private-key)))))

(defn installation-id
  "The App's installation id for owner/repo (config override or auto-resolved)."
  [jwt-token owner repo]
  (or (config-str [:github :app :installation-id])
      (-> (gh-get (format "%s/repos/%s/%s/installation" api-base owner repo)
                  (str "Bearer " jwt-token))
          (gh-ok "GitHub App installation lookup")
          :id)))

(defn installation-token
  "Mint a short-lived installation access token for owner/repo. Behaves like a
   PAT for both git operations and the REST API."
  [owner repo]
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

(defn create-pull-request
  "Open a PR via the GitHub REST API using the resolved token."
  [token owner repo {:keys [title body head base]}]
  (let [{:keys [status body]}
        (client/post (format "%s/repos/%s/%s/pulls" api-base owner repo)
                     {:headers (gh-headers (str "Bearer " token))
                      :content-type :json
                      :as :json
                      :coerce :always
                      :throw-exceptions false
                      :body (json/write-str {:title title
                                             :body body
                                             :head head
                                             :base base})})]
    (debug "create-pull-request status" status)
    (if (<= 200 status 299)
      body
      (throw (ex-info (str "GitHub PR creation failed: "
                           (or (:message body) status))
                      {:status status :body body})))))

;; ---------------------------------------------------------------------------
;; Orchestration
;; ---------------------------------------------------------------------------

(defn file-pr
  "End to end: clone, rebase on latest trunk, run Gemini, commit, push, open PR.
   Returns the GitHub PR map (with :html_url) or throws."
  [{:keys [owner repo instruction token]}]
  (let [dir (temp-dir)
        repo-dir dir
        ctx {:dir repo-dir}
        ;; embed the token only in env-free git operations via the remote URL
        clone-url (authed-clone-url token owner repo)
        branch (branch-name)]
    (try
      (info "cloning" (format "%s/%s" owner repo) "into" (str dir))
      (check-sh {} "git" "clone" "--depth" "50" clone-url (str repo-dir))
      ;; identify Yetibot as the committer
      (check-sh ctx "git" "config" "user.name" "Yetibot")
      (check-sh ctx "git" "config" "user.email" "yetibot@yetibot.com")
      (let [trunk (default-branch repo-dir)]
        (info "trunk is" trunk)
        ;; make sure we're on the latest trunk before branching
        (check-sh ctx "git" "checkout" trunk)
        (check-sh ctx "git" "fetch" "origin" trunk)
        (check-sh ctx "git" "pull" "--rebase" "origin" trunk)
        (check-sh ctx "git" "checkout" "-b" branch)
        ;; let Gemini do the work
        (let [gemini-result (run-gemini repo-dir instruction)]
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
        ;; best-effort cleanup of the temp checkout
        (try (sh* {} "rm" "-rf" (str dir))
             (catch Exception e (warn "failed to clean up" (str dir) e)))))))

(defn code-cmd
  "code <owner/repo> <instruction> # use Gemini to make the change and open a PR"
  {:yb/cat #{:util}}
  [{[_ repo-arg instruction] :match}]
  (cond
    (string/blank? (gemini-key))
    "Gemini is not configured. Set the `:gemini :key` config (YB_GEMINI_KEY)."

    (not (github-auth-configured?))
    "No GitHub auth configured. Set a GitHub App (`:github :app :id` + `:github :app :private-key`) or a token (`:github :token`)."

    :else
    (let [[owner repo] (parse-repo repo-arg)]
      (try
        (let [token (github-token owner repo)
              {:keys [html_url] :as pr}
              (file-pr {:owner owner
                        :repo repo
                        :instruction instruction
                        :token token})]
          (if html_url
            (format "Opened PR for %s/%s: %s" owner repo html_url)
            (str "Opened PR but got no URL back: " (pr-str pr))))
        (catch clojure.lang.ExceptionInfo e
          (if (= :no-changes (:type (ex-data e)))
            (do
              (info "code: gemini made no changes for" (str owner "/" repo))
              (format "Gemini didn't make any changes for %s/%s — try a more specific instruction."
                      owner repo))
            (do
              (error "code command failed" e)
              (str "Failed to open PR: " (.getMessage e)))))
        (catch Exception e
          (error "code command failed" e)
          (str "Failed to open PR: " (.getMessage e)))))))

;; Only register the command when both Gemini and GitHub are configured, in
;; keeping with the convention used by other optionally-configured commands.
(when (configured?)
  (cmd-hook #"code"
            #"^(\S+)\s+(.+)$" code-cmd))
