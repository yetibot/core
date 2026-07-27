(ns yetibot.core.test.commands.agent
  (:require
   [midje.sweet :refer [fact facts => anything]]
   [midje.checkers :refer [contains]]
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [yetibot.core.commands.agent :as agent])
  (:import
   [java.security KeyPairGenerator Signature]
   [java.util Arrays Base64]))

(facts "about redact"
  (fact "strips an embedded token from a url"
    (agent/redact "clone https://x-access-token:supersecret@github.com/yetibot/core.git")
    => "clone https://x-access-token:***@github.com/yetibot/core.git")
  (fact "strips a bare gh token"
    (agent/redact "export GH_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789")
    => (contains "***"))
  (fact "tolerates nil"
    (agent/redact nil) => nil))

(facts "about pr-urls"
  (fact "extracts and dedupes pull request urls"
    (agent/pr-urls "opened https://github.com/yetibot/core/pull/5 — see https://github.com/yetibot/core/pull/5")
    => ["https://github.com/yetibot/core/pull/5"])
  (fact "finds multiple"
    (agent/pr-urls "https://github.com/a/b/pull/1 https://github.com/c/d/pull/2")
    => ["https://github.com/a/b/pull/1" "https://github.com/c/d/pull/2"])
  (fact "empty when none"
    (agent/pr-urls "no prs here") => []))

(facts "about build-agent-prompt"
  (fact "tells gemini it can use the gh cli"
    (agent/build-agent-prompt "do x" nil nil) => (contains "gh"))
  (fact "instructs it to open a pull request"
    (agent/build-agent-prompt "do x" nil nil) => (contains "pull request"))
  (fact "asks for the final answer only (no narration)"
    (agent/build-agent-prompt "do x" nil nil) => (contains "final answer"))
  (fact "includes conversation context when present"
    (agent/build-agent-prompt "do x" "alice: hi" nil) => (contains "alice: hi"))
  (fact "omits the context section when blank"
    (agent/build-agent-prompt "do x" "" nil) => #(not (string/includes? % "Recent conversation")))
  (fact "tells gemini to use HTTPS (no SSH, no fork)"
    (agent/build-agent-prompt "do x" nil nil) => (contains "HTTPS"))
  (fact "warns the working dir is empty and to clone, never wait for files"
    (agent/build-agent-prompt "do x" nil nil) => (contains "EMPTY"))
  (fact "tells gemini to mention people with their <@id> token"
    (agent/build-agent-prompt "do x" nil nil) => (contains "<@id>"))
  (fact "includes the mention glossary when present"
    (agent/build-agent-prompt "do x" nil "• <@1> is Bob") => (contains "<@1> is Bob"))
  (fact "gives the bot an identity"
    (agent/build-agent-prompt "do x" nil nil) => (contains "Yetibot"))
  (fact "tells gemini to use yetibot tool to run yetibot commands"
    (agent/build-agent-prompt "do x" nil nil) => (contains "yetibot"))
  (fact "encourages searching channel history if needed"
    (agent/build-agent-prompt "do x" nil nil) => (contains "search the channel's history")))

(facts "about parse-json-response"
  (fact "pulls the response field"
    (agent/parse-json-response "{\"response\": \"hi there\", \"stats\": {}}") => "hi there")
  (fact "tolerates leading noise before the json"
    (agent/parse-json-response "warning: foo\n{\"response\": \"ok\"}") => "ok")
  (fact "nil on unparseable output"
    (agent/parse-json-response "not json at all") => nil))

(facts "about parse-json-raw"
  (fact "pulls the entire parsed map"
    (agent/parse-json-raw "{\"response\": \"hi there\", \"stats\": {\"a\": 1}}")
    => {:response "hi there" :stats {:a 1}})
  (fact "tolerates leading noise before the json"
    (agent/parse-json-raw "warning: foo\n{\"response\": \"ok\", \"stats\": {}}")
    => {:response "ok" :stats {}})
  (fact "nil on unparseable output"
    (agent/parse-json-raw "not json at all") => nil))

(facts "about final messages"
  (fact "say-working is a generic status, not the prompt"
    (agent/say-working) => (contains "Bonzi"))
  (fact "say-final shows Gemini's answer"
    (agent/say-final "Added the bagif command" nil) => (contains "Added the bagif command"))
  (fact "say-final appends relevant PR links"
    (agent/say-final "done" ["https://github.com/yetibot/core/pull/242"]) => (contains "pull/242"))
  (fact "say-final copes with a blank answer"
    (agent/say-final "" nil) => (contains "done"))
  (fact "say-final appends the model and cost footer"
    (agent/say-final "Added features" nil) => (contains "Sent via gemini-3.1-pro-preview | Cost: $1.00"))
  (fact "say-final accepts actual-cost and prints it correctly in the footer"
    (agent/say-final "Added features" nil 0.12) => (contains "Sent via gemini-3.1-pro-preview | Cost: $0.12")
    (agent/say-final "Added features" nil 1.234) => (contains "Sent via gemini-3.1-pro-preview | Cost: $1.23")
    (agent/say-final "Added features" nil 0.000475) => (contains "Sent via gemini-3.1-pro-preview | Cost: $0.0005")
    (agent/say-final "Added features" nil 0.004) => (contains "Sent via gemini-3.1-pro-preview | Cost: $0.0040")
    (agent/say-final "Added features" nil 0.0) => (contains "Sent via gemini-3.1-pro-preview | Cost: $0.00"))
  (fact "say-timeout names the limit"
    (agent/say-timeout 5) => (contains "5 min")))

(facts "about format-cost"
  (fact "nil or zero costs return 0.00"
    (agent/format-cost nil) => "0.00"
    (agent/format-cost 0) => "0.00"
    (agent/format-cost 0.0) => "0.00")
  (fact "costs under 0.01 are formatted to 4 decimal places"
    (agent/format-cost 0.000475) => "0.0005"
    (agent/format-cost 0.004) => "0.0040"
    (agent/format-cost 0.0099) => "0.0099")
  (fact "costs 0.01 or above are formatted to 2 decimal places"
    (agent/format-cost 0.01) => "0.01"
    (agent/format-cost 0.12) => "0.12"
    (agent/format-cost 1.234) => "1.23"))

(facts "about agent limits config defaults"
  (fact "default timeout is 15 minutes"
    (agent/agent-timeout-ms) => 900000)
  (fact "default max turns is 50"
    (agent/agent-max-turns) => 50)
  (fact "default model is the current Gemini 3.1 Pro"
    (agent/model) => "gemini-3.1-pro-preview"))

(facts "about mention-glossary"
  (fact "prefers the server nickname and keeps the <@id> token"
    (agent/mention-glossary [{:id "123" :username "alice" :global-name "Alice A"
                              :member {:nick "WandPotato"}}])
    => (contains "<@123> is WandPotato"))
  (fact "falls back to global-name then username when there's no nick"
    (agent/mention-glossary [{:id "99" :username "bob"}]) => (contains "<@99> is bob"))
  (fact "is empty when there are no mentions"
    (agent/mention-glossary nil) => ""))

(facts "about agent-cmd guards"
  (fact "replies in persona when nothing is configured"
    (agent/agent-cmd {:match ["agent do x" "do x"] :chat-source {}})
    => (contains "banana")
    (provided (agent/configured?) => false)))

;; --- GitHub auth: enough to mint GH_TOKEN for Gemini ---

(facts "about github auth config"
  (fact "app-configured? requires both id and private key"
    (agent/app-configured?) => true
    (provided (agent/app-id) => "123" (agent/app-private-key) => "KEY"))
  (fact "github-auth-configured? is satisfied by a PAT alone"
    (agent/github-auth-configured?) => true
    (provided (agent/app-configured?) => false (agent/github-pat) => "tok"))
  (fact "configured? needs gemini and github auth"
    (agent/configured?) => true
    (provided (agent/gemini-key) => "k" (agent/github-auth-configured?) => true)))

(facts "about github-token"
  (fact "uses the static PAT when no App is configured"
    (agent/github-token) => "pat"
    (provided (agent/app-configured?) => false (agent/github-pat) => "pat"))
  (fact "mints an App installation token, scoped to the whole installation"
    (agent/github-token) => "ghs_org"
    (provided
     (agent/app-configured?) => true
     (agent/app-jwt) => "jwt"
     (agent/any-installation-id "jwt") => 99
     (client/post "https://api.github.com/app/installations/99/access_tokens" anything)
     => {:status 201 :body {:token "ghs_org"}})))

;; --- RS256 JWT (JDK crypto, PKCS#8 + PKCS#1) ---

(defn- rsa-keypair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(defn- pem [label ^bytes der]
  (str "-----BEGIN " label "-----\n"
       (.encodeToString (Base64/getMimeEncoder) der)
       "\n-----END " label "-----\n"))

(defn- pkcs8-pem [kp] (pem "PRIVATE KEY" (.getEncoded (.getPrivate kp))))

(defn- pkcs1-pem [kp]
  (let [pkcs8 (.getEncoded (.getPrivate kp))]
    (pem "RSA PRIVATE KEY" (Arrays/copyOfRange pkcs8 26 (count pkcs8)))))

(defn- jwt-verifies? [token pub]
  (let [[h p s] (string/split token #"\.")
        signing-input (str h "." p)
        ok? (-> (doto (Signature/getInstance "SHA256withRSA")
                  (.initVerify pub)
                  (.update (.getBytes signing-input "UTF-8")))
                (.verify (.decode (Base64/getUrlDecoder) ^String s)))]
    {:valid? ok?
     :payload (json/read-str (String. (.decode (Base64/getUrlDecoder) ^String p))
                             :key-fn keyword)}))

(facts "about app-jwt"
  (fact "signs an RS256 token (PKCS#8 key) verifiable with the matching public key"
    (let [kp (rsa-keypair)]
      (jwt-verifies? (agent/app-jwt) (.getPublic kp))
      => (fn [{:keys [valid? payload]}] (and valid? (= "123" (:iss payload))))
      (provided (agent/app-id) => "123" (agent/app-private-key) => (pkcs8-pem kp))))
  (fact "also accepts a PKCS#1 key, the format GitHub issues App keys in"
    (let [kp (rsa-keypair)]
      (:valid? (jwt-verifies? (agent/app-jwt) (.getPublic kp))) => true
      (provided (agent/app-id) => "123" (agent/app-private-key) => (pkcs1-pem kp)))))

;; --- restart resilience: resume runs a restart left in-flight ---

(facts "about resume config defaults"
  (fact "default max attempts is 2 — the original plus one retry"
    (agent/agent-max-attempts) => 2))

(facts "about resume-action"
  (fact "resumes a fresh interrupted run"
    (agent/resume-action 1 1000 2 100000) => :resume)
  (fact "gives up once attempts reach the cap"
    (agent/resume-action 2 1000 2 100000) => :give-up)
  (fact "treats a run older than the cutoff as stale"
    (agent/resume-action 1 200000 2 100000) => :stale)
  (fact "the attempts cap wins over staleness"
    (agent/resume-action 2 200000 2 100000) => :give-up))

(facts "about resume-request"
  (fact "prepends a dedup note so a resumed run won't open a duplicate PR"
    (agent/resume-request "add a bagif command") => (contains "gh pr list"))
  (fact "keeps the original request text"
    (agent/resume-request "add a bagif command") => (contains "add a bagif command")))

(facts "about thread-context"
  (fact "returns empty string if the channel is not a thread (type is not 10, 11, or 12)"
    (#'agent/thread-context "channel-id-123") => ""
    (provided
      (#'agent/rest-conn) => "mock-conn"
      (discljord.messaging/get-channel! "mock-conn" "channel-id-123") => (atom {:type 0 :name "general"})))

  (fact "returns thread context if the channel is a thread (type 11) with multiple messages"
    (#'agent/thread-context "thread-id-456") => "[thread topic] cool-thread\nalice: hello\nbob: world"
    (provided
      (#'agent/rest-conn) => "mock-conn"
      (discljord.messaging/get-channel! "mock-conn" "thread-id-456") => (atom {:type 11 :name "cool-thread"})
      (#'agent/all-channel-messages "thread-id-456") => [{:author {:username "alice"} :content "hello" :timestamp 1}
                                                         {:author {:username "bob"} :content "world" :timestamp 2}]))

  (fact "returns empty string if the channel is a thread but has 1 or fewer messages (first message/prompt only)"
    (#'agent/thread-context "thread-id-789") => ""
    (provided
      (#'agent/rest-conn) => "mock-conn"
      (discljord.messaging/get-channel! "mock-conn" "thread-id-789") => (atom {:type 11 :name "cool-thread"})
      (#'agent/all-channel-messages "thread-id-789") => [{:author {:username "alice"} :content "hello" :timestamp 1}])))

(facts "about agent subcommands"
  (fact "agent-list-commands-cmd returns available commands in JSON"
    (agent/agent-list-commands-cmd {}) => {:result/value "{\"commands\":[\"cmd1\",\"cmd2\"]}"}
    (provided
      (yetibot.core.models.help/get-docs) => {"cmd1" "doc1" "cmd2" "doc2"}))

  (fact "agent-list-aliases-cmd returns available aliases in JSON"
    (agent/agent-list-aliases-cmd {}) => {:result/value "{\"aliases\":[{\"cmd-name\":\"hello\",\"cmd\":\"echo hello\"}]}"}
    (provided
      (yetibot.core.db.alias/find-all) => [{:cmd "echo hello" :cmd-name "hello"}]))

  (fact "agent-run-cmd evaluates a yetibot command"
    (agent/agent-run-cmd {:match ["agent run echo hello" "echo hello"]}) => {:result/value "hello"}
    (provided
      (yetibot.core.handler/record-and-run-raw "echo hello" anything nil anything) => [{:result "hello"}])) )
