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
    (agent/build-agent-prompt "do x" nil) => (contains "gh"))
  (fact "instructs it to open a pull request"
    (agent/build-agent-prompt "do x" nil) => (contains "pull request"))
  (fact "asks for the final answer only (no narration)"
    (agent/build-agent-prompt "do x" nil) => (contains "final answer"))
  (fact "includes conversation context when present"
    (agent/build-agent-prompt "do x" "alice: hi") => (contains "alice: hi"))
  (fact "omits the context section when blank"
    (agent/build-agent-prompt "do x" "") => #(not (string/includes? % "Conversation so far")))
  (fact "tells gemini to use HTTPS (no SSH, no fork)"
    (agent/build-agent-prompt "do x" nil) => (contains "HTTPS"))
  (fact "warns the working dir is empty and to clone, never wait for files"
    (agent/build-agent-prompt "do x" nil) => (contains "EMPTY"))
  (fact "notes that @name is a person"
    (agent/build-agent-prompt "do x" nil) => (contains "@name")))

(facts "about parse-json-response"
  (fact "pulls the response field"
    (agent/parse-json-response "{\"response\": \"hi there\", \"stats\": {}}") => "hi there")
  (fact "tolerates leading noise before the json"
    (agent/parse-json-response "warning: foo\n{\"response\": \"ok\"}") => "ok")
  (fact "nil on unparseable output"
    (agent/parse-json-response "not json at all") => nil))

(facts "about final messages"
  (fact "say-working is a generic status, not the prompt"
    (agent/say-working) => (contains "grug"))
  (fact "say-final shows Gemini's answer"
    (agent/say-final "Added the bagif command" nil) => (contains "Added the bagif command"))
  (fact "say-final appends relevant PR links"
    (agent/say-final "done" ["https://github.com/yetibot/core/pull/242"]) => (contains "pull/242"))
  (fact "say-final copes with a blank answer"
    (agent/say-final "" nil) => (contains "done"))
  (fact "say-timeout names the limit"
    (agent/say-timeout 5) => (contains "5 min"))
  (fact "say-busy explains the agent is occupied"
    (agent/say-busy) => (contains "grug")))

(facts "about agent limits config defaults"
  (fact "default timeout is 20 minutes"
    (agent/agent-timeout-ms) => 1200000)
  (fact "default max turns is 50"
    (agent/agent-max-turns) => 50))

(facts "about resolve-mentions"
  (fact "replaces a user mention with @display-name"
    (agent/resolve-mentions "say hi to <@123>" [{:id "123" :username "alice" :global-name "Alice A"}])
    => "say hi to @Alice A")
  (fact "handles the <@!id> nickname form and falls back to username"
    (agent/resolve-mentions "ping <@!99>" [{:id "99" :username "bob"}]) => "ping @bob")
  (fact "leaves text untouched when there are no mentions"
    (agent/resolve-mentions "no mentions here" nil) => "no mentions here"))

(facts "about agent-cmd guards"
  (fact "replies in persona when nothing is configured"
    (agent/agent-cmd {:match ["agent do x" "do x"] :chat-source {}})
    => (contains "grug")
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
