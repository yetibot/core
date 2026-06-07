(ns yetibot.core.commands.agent
  "A meme-loving coding agent. `agent <prompt>` hands the request to the Gemini
   CLI running headlessly as an autonomous agent: Gemini uses the authenticated
   `gh` CLI and `git` to find the right repo(s), make the change, and open pull
   requests itself. Yetibot's job is just to run it and relay what it's doing,
   live, into a chat thread — in the playful persona of Bonzi Buddy.

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
   [clojure.java.jdbc :as jdbc]
   [discljord.messaging :as discord]
   [taoensso.timbre :refer [debug info warn error]]
   [yetibot.core.adapters.adapter :as a]
   [yetibot.core.chat :as chat]
   [yetibot.core.config :refer [get-config]]
   [yetibot.core.db :as db]
   [yetibot.core.db.agent-run :as agent-run]
   [yetibot.core.db.alias :as db.alias]
   [yetibot.core.db.util :as db.util]
   [yetibot.core.hooks :refer [cmd-hook]]
   [yetibot.core.interpreter :as interp]
   [yetibot.core.loader :as loader]
   [yetibot.core.models.help :as help]
   [yetibot.core.commands.alias :as alias]
   [yetibot.core.handler :refer [record-and-run-raw]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.security KeyFactory Signature]
   [java.security.spec PKCS8EncodedKeySpec]
   [java.util Base64])
  (:gen-class))

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

;; Gemini model for the agent. Defaults to the strongest current Pro model;
;; override with [:gemini :agent :model] (e.g. "gemini-3.5-flash") for speed.
(defn model [] (or (config-str [:gemini :agent :model]) "gemini-3.1-pro-preview"))

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
(defn agent-timeout-ms [] (config-num [:gemini :agent :timeout-ms] 900000))
(defn agent-max-turns [] (config-num [:gemini :agent :max-turns] 50))
;; how long a leftover scratch dir may linger before the sweep reaps it (1 day)
(defn agent-workdir-max-age-ms [] (config-num [:gemini :agent :workdir-max-age-ms] 86400000))

;; Restart resilience: an in-flight run is persisted and, after a restart that
;; killed it, re-dispatched on the next boot.
;;   max-attempts    - total runs (original + retries) before giving up
;;   resume-stale-ms - skip resuming a run older than this (default 6h)
;;   resume-ready-ms - how long to wait at boot for the DB + an adapter to be live
;;   resume-stagger-ms - gap between resumed dispatches (2 cores, uncapped concurrency)
(defn agent-max-attempts [] (config-num [:gemini :agent :max-attempts] 2))
(defn agent-resume-stale-ms [] (config-num [:gemini :agent :resume-stale-ms] 21600000))
(defn agent-resume-ready-ms [] (config-num [:gemini :agent :resume-ready-ms] 60000))
(defn agent-resume-stagger-ms [] (config-num [:gemini :agent :resume-stagger-ms] 3000))

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
;; Persona — Bonzi Buddy voice for the agent's chat messages only.
;; ---------------------------------------------------------------------------

;; Yetibot is the middleman. One transient status message shows the latest step
;; while Gemini works; it's deleted at the end and replaced by a clean summary.

(defn say-working
  "Transient status message, deleted once Gemini returns its final answer."
  []
  "🐵 Bonzi Buddy is swinging into action! Please wait a moment…")

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
  (str "🍌 Oh no! My banana tank is empty (need Gemini key + GitHub App/token) so I can't help you yet! 🍌"))

(defn say-resuming []
  "🐵 Bonzi got bumped by a reboot, but I'm swinging back into action!…")

(defn say-gave-up []
  "💀 Bonzi got too dizzy from restarts — please try again in a bit!")

(defn say-stale []
  "💤 Bonzi fell asleep waiting — ask again?")

(defn resume-request
  "Prefix a request for a resumed run so Gemini continues whatever its interrupted
   attempt already started instead of duplicating it."
  [request]
  (str "(Your previous attempt at this was interrupted by a restart before it could "
       "finish. Before doing anything else, run `gh pr list` and check for a branch "
       "you may have already pushed for this — if one exists, continue and finish "
       "that work rather than opening a duplicate PR.)\n\n"
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

(defn mention-glossary
  "A note mapping each Discord mention in the message to the person's server
   display name and their <@id> token. The request keeps its <@id> tokens so
   Gemini's reply can ping people (Discord renders their server name); this just
   tells Gemini who's who. Empty string when there are no mentions."
  [mentions]
  (let [lines (for [{:keys [id username global-name member]} mentions
                    :when id]
                (str "• <@" id "> is " (or (:nick member) global-name username (str "user " id))))]
    (if (seq lines)
      (str "People referenced in the request (write their <@id> token verbatim to "
           "@-mention/ping them in your reply — that shows their server name):\n"
           (string/join "\n" lines))
      "")))

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

(defn build-agent-prompt [request context mentions]
  (str "You are Yetibot — the team's coding-agent bot, appearing as @Yetibot in "
       "their chat. A teammate gives you a request and you carry out THAT request "
       "end to end through the `gh` CLI and `git`, running non-interactively.\n\n"
       "SCOPE — read this first:\n"
       "- Do exactly what the request asks, and nothing more. Touch only the "
       "specific repo and files the request needs.\n"
       "- Do NOT survey or list other repos, and do NOT look at CI, tests, or PR "
       "status unless the request is explicitly about those — a request to change "
       "code is not a request to audit CI.\n"
       "- If you can't tell what the request refers to, reply briefly asking for "
       "the missing detail. NEVER invent work or report an unrelated summary like "
       "\"checked all repos, everything green\".\n"
       "- If you need more background context than what is provided in the thread "
       "context, you are highly encouraged to search the entire channel's history "
       "using the `yetibot` tool with the `history` command (e.g., `history` or "
       "`history | grep keyword`). It is your job as an autonomous agent to "
       "perform this extra search when needed!\n\n"
       "The codebase (so you can go straight to the right place — don't rediscover "
       "it). The org is `yetibot`:\n"
       "- `yetibot/core` — the library: chat commands, adapters, and most logic.\n"
       "  • `src/yetibot/core/commands/<name>.clj` — one file per chat command, "
       "loaded dynamically (a new command is just a new file).\n"
       "  • `src/yetibot/core/adapters/` — chat adapters (discord, slack, irc, "
       "mattermost).\n"
       "  • `src/yetibot/core/util/` — shared utils (e.g. `gemini.clj`: Gemini "
       "image generation, Veo video, and the monthly budget).\n"
       "  • `src/yetibot/core/webapp/routes/` — web routes (e.g. `images.clj` "
       "serves generated media).\n"
       "  • `test/yetibot/core/test/…` mirrors `src/`; tests are midje, run with "
       "`lein test`.\n"
       "- `yetibot/yetibot` — the deployable bot; pins `yetibot/core` and holds "
       "config/deploy.\n"
       "Most command and feature work is in `yetibot/core` — clone just that.\n\n"
       "Your working directory is an EMPTY scratch dir — there is no code here, so "
       "get what you need yourself; never wait for files or ask the user to add "
       "code (GH_TOKEN is set, you have write access).\n"
       "- Code change: clone the one relevant repo over HTTPS (`gh repo clone "
       "<owner>/<repo>` — never SSH, never fork), set git config user.name "
       "'Yetibot' / user.email 'yetibot@yetibot.com', make a minimal change on a "
       "new branch, push to origin, and `gh pr create`.\n"
       "- Question: just answer it; clone only if the answer needs the code.\n\n"
       "Tools (shell): `gh` (authenticated) and `git`.\n\n"
       "Introspecting and running Yetibot commands:\n"
       "You have a built-in `yetibot` tool. You can use it to execute any built-in Yetibot command or alias directly. Do NOT run them as shell commands; always use the `yetibot` tool call.\n"
       "Call the `yetibot` tool with the `command` argument (e.g. `{\"command\": \"temps\"}`). For example:\n"
       "- `yetibot` with command \"agent list-commands\": returns all available built-in commands as a JSON map\n"
       "- `yetibot` with command \"agent list-aliases\": returns all configured command aliases as a JSON map\n"
       "- `yetibot` with command \"temps\": runs the `temps` alias to get weather/temp data\n"
       "- `yetibot` with command \"kroki <payload>\": generates a chart using kroki\n\n"
       "For example, you can call the `yetibot` tool with \"agent list-aliases\", find a weather/temp alias, run it using the `yetibot` tool to get its data, and then pass that data to another command like `yetibot` with \"kroki ...\" to generate a chart!\n\n"
       (when-not (string/blank? mentions) (str mentions "\n\n"))
       (when-not (string/blank? context)
         (str "This thread's conversation so far, for REFERENCE ONLY — background, "
              "not a task list. Use it only to resolve what the request refers to "
              "(e.g. a \"retry\" or follow-up points back to an earlier ask here). "
              "Do NOT act on it on your own or investigate anything just because "
              "it's mentioned:\n────\n" (string/trim context) "\n────\n\n"))
       "The teammate's request:\n" (string/trim request) "\n\n"
       "When you mention or address a person, write their Discord mention token "
       "<@id> verbatim (e.g. <@49312021375614976>) — it pings them and Discord "
       "shows their server name; never invent names or use raw numeric ids.\n\n"
       "Now do the work, then reply with ONLY your final answer — concise, direct, "
       "and in the brief, playful, and cheerful persona of Bonzi Buddy, the classic purple "
       "gorilla Windows assistant (keep the facts exact). Do NOT include any step-by-step "
       "narration, internal thinking, reasoning, or lists of justifications/explanations. "
       "Just provide the final conclusion or result. Reference any pull requests as full "
       "URLs (https://github.com/owner/repo/pull/123), never the #123 shorthand."))

;; Authenticate git pushes to github.com with GH_TOKEN, so the agent's plain
;; `git push` over HTTPS works without prompting (the token alone only auths the
;; `gh` API, not git). The helper reads GH_TOKEN from the environment at runtime.
(def ^:private git-credential-helper
  "!f() { echo username=x-access-token; echo \"password=$GH_TOKEN\"; }; f")

(defn- kill-tree!
  "Forcibly kill a process and all of its descendants. Gemini spawns bun/git/gh
   children; killing only the parent would orphan them and pile up CPU load."
  [^Process proc]
  (let [descendants (doall (iterator-seq (.iterator ^java.util.stream.Stream (.descendants proc))))]
    (doseq [^java.lang.ProcessHandle h (cons (.toHandle proc) descendants)]
      (try (.destroyForcibly h) (catch Exception _ nil)))))

(defn run-gemini-agent
  "Run the Gemini CLI headlessly with structured JSON output (no intermediate
   narration on stdout; stderr is discarded). Returns
   {:response <final answer text or nil> :exit n :timed-out bool}."
  [workdir request context mentions token]
  ;; cap the agent's turn budget via a workspace settings file, configure custom tools, and disable model reasoning to keep responses concise
  (let [settings-dir (io/file workdir ".gemini")]
    (.mkdirs settings-dir)
    (spit (io/file settings-dir "settings.json")
          (json/write-str {:maxSessionTurns (agent-max-turns)
                           :tools {:discoveryCommand "./yetibot-tool.py --list"
                                   :callCommand "./yetibot-tool.py"}
                           :modelConfig {:generateContentConfig {:thinkingConfig {:thinkingBudget 0}}}})))
  ;; write the yetibot custom tool script
  (let [yetibot-tool-script (io/file workdir "yetibot-tool.py")]
    (spit yetibot-tool-script
          (str "#!/usr/bin/env python3\n"
               "import sys\n"
               "import json\n"
               "import urllib.request\n"
               "import urllib.parse\n"
               "import os\n\n"
               "if len(sys.argv) > 1 and sys.argv[1] == \"--list\":\n"
               "    tools = [\n"
               "        {\n"
               "            \"name\": \"yetibot\",\n"
               "            \"description\": \"Execute any built-in Yetibot command or alias. Examples: 'temps', 'kroki <payload>'.\",\n"
               "            \"inputSchema\": {\n"
               "                \"type\": \"object\",\n"
               "                \"properties\": {\n"
               "                    \"command\": {\n"
               "                        \"type\": \"string\",\n"
               "                        \"description\": \"The Yetibot command or alias with its arguments to execute.\"\n"
               "                    }\n"
               "                },\n"
               "                \"required\": [\"command\"]\n"
               "            }\n"
               "        }\n"
               "    ]\n"
               "    print(json.dumps(tools))\n"
               "    sys.exit(0)\n\n"
               "if len(sys.argv) > 1 and sys.argv[1] == \"yetibot\":\n"
               "    try:\n"
               "        input_data = json.loads(sys.stdin.read())\n"
               "        cmd = input_data.get(\"command\", \"\")\n"
               "    except Exception as e:\n"
               "        print(json.dumps({\n"
               "            \"content\": [{\"type\": \"text\", \"text\": f\"Error parsing stdin JSON: {str(e)}\"}],\n"
               "            \"isError\": True\n"
               "        }))\n"
               "        sys.exit(0)\n\n"
               "    if not cmd:\n"
               "        print(json.dumps({\n"
               "            \"content\": [{\"type\": \"text\", \"text\": \"Error: command parameter is missing\"}],\n"
               "            \"isError\": True\n"
               "        }))\n"
               "        sys.exit(0)\n\n"
               "    if cmd.startswith(\"agent \"):\n"
               "        payload = cmd\n"
               "    else:\n"
               "        payload = f\"agent run {cmd}\"\n\n"
               "    port = os.environ.get(\"YETIBOT_PORT\", \"3003\")\n"
               "    url = f\"http://localhost:{port}/api\"\n"
               "    data = urllib.parse.urlencode({\n"
               "        \"chat-source\": \"{:adapter :agent :room \\\"agent-room\\\"}\",\n"
               "        \"command\": payload\n"
               "    }).encode(\"utf-8\")\n\n"
               "    try:\n"
               "        req = urllib.request.Request(url, data=data, method=\"POST\")\n"
               "        with urllib.request.urlopen(req) as response:\n"
               "            res_text = response.read().decode(\"utf-8\")\n"
               "        print(json.dumps({\n"
               "            \"content\": [{\"type\": \"text\", \"text\": res_text}],\n"
               "            \"isError\": False\n"
               "        }))\n"
               "    except Exception as e:\n"
               "        print(json.dumps({\n"
               "            \"content\": [{\"type\": \"text\", \"text\": f\"API request failed: {str(e)}\"}],\n"
               "            \"isError\": True\n"
               "        }))\n"
               "    sys.exit(0)\n"))
    (.setExecutable yetibot-tool-script true))
  (let [pb (doto (ProcessBuilder. [(cli-bin) "--yolo" "--output-format" "json"
                                   "--model" (model)
                                   "--prompt" (build-agent-prompt request context mentions)])
             (.directory (io/file workdir))
             (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))]
    (doto (.environment pb)
      (.put "GEMINI_API_KEY" (gemini-key))
      (.put "GEMINI_CLI_TRUST_WORKSPACE" "true")
      (.put "GH_TOKEN" (or token ""))
      (.put "YETIBOT_PORT" (str (or (System/getenv "PORT") "3003")))
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
  "Async body: mint a token, run Gemini headlessly, then delete the transient
   status message and post one clean final reply — Gemini's answer plus links to
   any relevant PRs. No intermediate narration."
  [{:keys [request target context-channel on-discord status-id mentions run-id]}]
  (binding [chat/*target* target]
    (sweep-stale-workdirs! (agent-workdir-max-age-ms))
    (let [dir (work-dir target)]
      (try
        (let [context (when on-discord (thread-context context-channel))
              token (github-token)
              {:keys [response exit timed-out]} (run-gemini-agent dir request context mentions token)
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
               (catch Exception e (warn "cleanup failed" (str dir) e)))
          (clear-run! run-id))))))

(defn agent-cmd
  "agent <prompt> # hand the request to Gemini (gh+git) and reply with its answer"
  {:yb/cat #{:util}}
  [{[_ request] :match chat-source :chat-source}]
  (cond
    (not (configured?)) (say-unconfigured)
    :else
    (let [adapter chat/*adapter*
          {:keys [raw-event]} chat-source
          ;; keep <@id> tokens in the request; give Gemini a name glossary so its
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
  "Re-run an interrupted run, restoring its adapter + thread and nudging Gemini to
   continue any work it already started."
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

;; Register only when Gemini + GitHub auth are configured.
(when (configured?)
  (cmd-hook #"agent"
            #"list-commands" agent-list-commands-cmd
            #"list-aliases" agent-list-aliases-cmd
            #"run\s+(.+)" agent-run-cmd
            #"(?s)(.+)" agent-cmd)
  (resume-interrupted-runs!))
