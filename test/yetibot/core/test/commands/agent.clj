(ns yetibot.core.test.commands.agent
  (:require
   [midje.sweet :refer [fact facts => anything throws =throws=>]]
   [midje.checkers :refer [contains]]
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [yetibot.core.commands.agent :as agent])
  (:import
   [java.security KeyPairGenerator Signature]
   [java.util Arrays Base64]))

(facts "about parse-repo"
  (fact "splits owner/repo"
    (agent/parse-repo "yetibot/core") => ["yetibot" "core"])
  (fact "uses default org for a bare repo name"
    (agent/parse-repo "core") => ["yetibot" "core"]
    (provided (agent/default-org) => "yetibot")))

(facts "about explicit-repos"
  (fact "extracts owner/repo tokens from a prompt"
    (agent/explicit-repos "fix yetibot/core and yetibot/yetibot please")
    => ["yetibot/core" "yetibot/yetibot"])
  (fact "dedupes"
    (agent/explicit-repos "yetibot/core then yetibot/core") => ["yetibot/core"])
  (fact "is empty for a plain English prompt"
    (agent/explicit-repos "make the banana budget bigger") => []))

(facts "about discover-repos"
  (fact "prefers explicit repos and skips Gemini"
    (agent/discover-repos "patch yetibot/core now") => ["yetibot/core"])
  (fact "asks Gemini against the org list when none are explicit"
    (agent/discover-repos "raise the image budget")
    => ["yetibot/core"]
    (provided (agent/list-org-repos "yetibot") => ["yetibot/core" "yetibot/yetibot"]
              (agent/gemini-text anything) => "yetibot/core")))

(facts "about authed-clone-url"
  (fact "embeds the token for auth"
    (agent/authed-clone-url "secret" "yetibot" "core")
    => "https://x-access-token:secret@github.com/yetibot/core.git"))

(facts "about branch-name"
  (fact "is namespaced and unique-ish"
    (agent/branch-name) => #(string/starts-with? % "yetibot/agent-")))

(facts "about pr-title"
  (fact "capitalizes and collapses whitespace"
    (agent/pr-title "increase   banana budget") => "Increase banana budget")
  (fact "truncates very long instructions"
    (count (agent/pr-title (apply str (repeat 200 "x")))) => 72)
  (fact "handles blank instruction"
    (agent/pr-title "   ") => "Yetibot change"))

(facts "about pr-body"
  (fact "includes the instruction and embeds gemini output"
    (agent/pr-body "do a thing" "some output")
    => (contains "do a thing"))
  (fact "embeds the gemini output"
    (agent/pr-body "x" "some output") => (contains "some output")))

(facts "about redact"
  (fact "strips an embedded github token from a clone url"
    (agent/redact "git clone https://x-access-token:supersecret@github.com/yetibot/core.git")
    => "git clone https://x-access-token:***@github.com/yetibot/core.git")
  (fact "tolerates nil"
    (agent/redact nil) => nil))

(facts "about build-gemini-prompt"
  (fact "tells the agent it can use the gh cli"
    (agent/build-gemini-prompt "do x" nil) => (contains "gh"))
  (fact "includes thread context when present"
    (agent/build-gemini-prompt "do x" "alice: hi") => (contains "alice: hi"))
  (fact "omits the context section when blank"
    (agent/build-gemini-prompt "do x" "") => #(not (string/includes? % "context so far"))))

(facts "about run-gemini"
  (fact "invokes the cli with model/yolo/prompt and exports the keys + GH_TOKEN"
    (agent/run-gemini "/tmp/repo" "increase banana budget" "" "ghs_tok")
    => {:exit 0 :out "done" :err ""}
    (provided
     (agent/cli-bin) => "gemini"
     (agent/gemini-key) => "test-key"
     (agent/check-sh {:dir "/tmp/repo"
                      :env {"GEMINI_API_KEY" "test-key"
                            "GEMINI_CLI_TRUST_WORKSPACE" "true"
                            "GH_TOKEN" "ghs_tok"}}
                     "gemini" "--model" "gemini-2.5-pro" "--yolo"
                     "--prompt" anything)
     => {:exit 0 :out "done" :err ""})))

(facts "about create-pull-request"
  (fact "returns the PR body on success"
    (agent/create-pull-request "tok" "yetibot" "core"
                               {:title "T" :body "B" :head "h" :base "master"})
    => {:html_url "https://github.com/yetibot/core/pull/1"}
    (provided
     (client/post "https://api.github.com/repos/yetibot/core/pulls" anything)
     => {:status 201 :body {:html_url "https://github.com/yetibot/core/pull/1"}}))
  (fact "throws on a non-2xx response"
    (agent/create-pull-request "tok" "yetibot" "core"
                               {:title "T" :body "B" :head "h" :base "master"})
    => (throws clojure.lang.ExceptionInfo)
    (provided
     (client/post anything anything) => {:status 422 :body {:message "Validation Failed"}})))

(facts "about run-repo"
  (fact "returns the PR url on success"
    (agent/run-repo "yetibot/core" "do x" "")
    => {:repo "yetibot/core" :url "https://github.com/yetibot/core/pull/7"}
    (provided (agent/github-token "yetibot" "core") => "tok"
              (agent/file-pr anything)
              => {:html_url "https://github.com/yetibot/core/pull/7"}))
  (fact "flags no-changes"
    (agent/run-repo "yetibot/core" "nothing" "")
    => {:repo "yetibot/core" :no-changes true}
    (provided (agent/github-token "yetibot" "core") => "tok"
              (agent/file-pr anything)
              =throws=> (ex-info "Gemini did not make any changes" {:type :no-changes})))
  (fact "captures errors without throwing"
    (agent/run-repo "yetibot/core" "boom" "")
    => (contains {:repo "yetibot/core" :error anything})
    (provided (agent/github-token "yetibot" "core") => "tok"
              (agent/file-pr anything) =throws=> (ex-info "kaboom" {}))))

(facts "about persona"
  (fact "say-done lists PR urls for successes"
    (agent/say-done [{:repo "yetibot/core" :url "https://x/pull/1"}])
    => (contains "https://x/pull/1"))
  (fact "say-done shows errors for failures"
    (agent/say-done [{:repo "yetibot/core" :error "boom"}]) => (contains "boom")))

(facts "about agent-cmd guards"
  (fact "replies in persona when nothing is configured"
    (agent/agent-cmd {:match ["agent do x" "do x"] :chat-source {}})
    => (contains "grug")
    (provided (agent/configured?) => false)))

;; --- GitHub App auth (RS256 JWT, JDK crypto, PKCS#8 + PKCS#1) ---

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

(facts "about github auth config"
  (fact "app-configured? requires both id and private key"
    (agent/app-configured?) => true
    (provided (agent/app-id) => "123" (agent/app-private-key) => "KEY"))
  (fact "github-auth-configured? is satisfied by a PAT alone"
    (agent/github-auth-configured?) => true
    (provided (agent/app-configured?) => false (agent/github-pat) => "tok")))

(facts "about github-token resolution"
  (fact "uses the static PAT when no App is configured"
    (agent/github-token "yetibot" "core") => "pat"
    (provided (agent/app-configured?) => false (agent/github-pat) => "pat"))
  (fact "mints an installation token when an App is configured"
    (agent/github-token "yetibot" "core") => "ghs_installation"
    (provided (agent/app-configured?) => true
              (agent/installation-token "yetibot" "core") => "ghs_installation")))

(facts "about installation-token"
  (fact "exchanges an app JWT for an installation access token"
    (agent/installation-token "yetibot" "core") => "ghs_abc"
    (provided
     (agent/app-jwt) => "jwt"
     (agent/installation-id "jwt" "yetibot" "core") => 42
     (client/post "https://api.github.com/app/installations/42/access_tokens" anything)
     => {:status 201 :body {:token "ghs_abc"}})))
