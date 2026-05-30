(ns yetibot.core.test.commands.code
  (:require
   [midje.sweet :refer [fact facts => anything throws =throws=>]]
   [midje.checkers :refer [contains]]
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [yetibot.core.commands.code :as code])
  (:import
   [java.security KeyPairGenerator Signature]
   [java.util Arrays Base64]))

(facts "about parse-repo"
  (fact "splits owner/repo"
    (code/parse-repo "yetibot/core") => ["yetibot" "core"])

  (fact "trims whitespace"
    (code/parse-repo "  yetibot/core  ") => ["yetibot" "core"])

  (fact "uses default org for a bare repo name"
    (code/parse-repo "core") => ["yetibot" "core"]
    (provided (code/default-org) => "yetibot")))

(facts "about authed-clone-url"
  (fact "embeds the token for auth"
    (code/authed-clone-url "secret" "yetibot" "core") =>
    "https://x-access-token:secret@github.com/yetibot/core.git"))

(facts "about branch-name"
  (fact "is namespaced and unique-ish"
    (code/branch-name) => #(string/starts-with? % "yetibot/code-")))

(facts "about pr-title"
  (fact "capitalizes and collapses whitespace"
    (code/pr-title "increase   banana budget") => "Increase banana budget")

  (fact "truncates very long instructions"
    (count (code/pr-title (apply str (repeat 200 "x")))) => 72)

  (fact "marks truncated titles with an ellipsis"
    (string/ends-with? (code/pr-title (apply str (repeat 200 "x"))) "...") => true)

  (fact "handles blank instruction"
    (code/pr-title "   ") => "Yetibot change"))

(facts "about pr-body"
  (fact "includes the instruction and model"
    (code/pr-body "do a thing" "I did the thing") =>
    (contains "do a thing"))

  (fact "embeds gemini output when present"
    (code/pr-body "x" "some output") => (contains "some output")))

(facts "about redact"
  (fact "strips an embedded github token from a clone url"
    (code/redact "git clone https://x-access-token:supersecret@github.com/yetibot/core.git")
    => "git clone https://x-access-token:***@github.com/yetibot/core.git")

  (fact "leaves token-free strings untouched"
    (code/redact "nothing to redact here") => "nothing to redact here")

  (fact "tolerates nil"
    (code/redact nil) => nil))

(facts "about run-gemini"
  (fact "invokes the configured cli with model, yolo, and prompt, passing the API key in env"
    (code/run-gemini "/tmp/repo" "increase banana budget") => {:exit 0 :out "done" :err ""}
    (provided
     (code/cli-bin) => "gemini"
     (code/gemini-key) => "test-key"
     (code/check-sh {:dir "/tmp/repo" :env {"GEMINI_API_KEY" "test-key"}}
                    "gemini" "--model" "gemini-2.5-pro" "--yolo"
                    "--prompt" anything)
     => {:exit 0 :out "done" :err ""})))

(facts "about create-pull-request"
  (fact "returns the PR body on success and includes auth + payload"
    (code/create-pull-request "tok" "yetibot" "core"
                              {:title "T" :body "B" :head "h" :base "master"})
    => {:html_url "https://github.com/yetibot/core/pull/1"}
    (provided
     (client/post
      "https://api.github.com/repos/yetibot/core/pulls"
      anything)
     => {:status 201 :body {:html_url "https://github.com/yetibot/core/pull/1"}}))

  (fact "throws on a non-2xx response"
    (code/create-pull-request "tok" "yetibot" "core"
                              {:title "T" :body "B" :head "h" :base "master"})
    => (throws clojure.lang.ExceptionInfo)
    (provided
     (client/post anything anything)
     => {:status 422 :body {:message "Validation Failed"}})))

(defn- rsa-keypair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(defn- pem [label ^bytes der]
  (str "-----BEGIN " label "-----\n"
       (.encodeToString (Base64/getMimeEncoder) der)
       "\n-----END " label "-----\n"))

(defn- pkcs8-pem [kp]
  (pem "PRIVATE KEY" (.getEncoded (.getPrivate kp))))

(defn- pkcs1-pem
  "Derive a PKCS#1 PEM from a generated key by extracting the PKCS#1 body (the
   PrivateKeyInfo OCTET STRING) out of its PKCS#8 encoding. For a 2048-bit key
   that body sits after a fixed 26-byte PKCS#8 prefix."
  [kp]
  (let [pkcs8 (.getEncoded (.getPrivate kp))]
    (pem "RSA PRIVATE KEY" (Arrays/copyOfRange pkcs8 26 (count pkcs8)))))

(defn- jwt-verifies?
  "Split a JWT, verify its RS256 signature against pub, and return the payload."
  [token pub]
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
      (jwt-verifies? (code/app-jwt) (.getPublic kp))
      => (fn [{:keys [valid? payload]}] (and valid? (= "123" (:iss payload))))
      (provided (code/app-id) => "123" (code/app-private-key) => (pkcs8-pem kp))))

  (fact "also accepts a PKCS#1 key, the format GitHub issues App keys in"
    (let [kp (rsa-keypair)]
      (jwt-verifies? (code/app-jwt) (.getPublic kp)) => (fn [r] (:valid? r))
      (provided (code/app-id) => "123" (code/app-private-key) => (pkcs1-pem kp)))))

(facts "about github auth config"
  (fact "app-configured? requires both an id and a private key"
    (code/app-configured?) => true
    (provided (code/app-id) => "123" (code/app-private-key) => "KEY"))

  (fact "app-configured? is false without a private key"
    (code/app-configured?) => false
    (provided (code/app-id) => "123" (code/app-private-key) => nil))

  (fact "github-auth-configured? is satisfied by a PAT alone"
    (code/github-auth-configured?) => true
    (provided (code/app-configured?) => false (code/github-pat) => "tok"))

  (fact "github-auth-configured? is satisfied by an App alone"
    (code/github-auth-configured?) => true
    (provided (code/app-configured?) => true))

  (fact "github-auth-configured? is false with neither"
    (code/github-auth-configured?) => false
    (provided (code/app-configured?) => false (code/github-pat) => nil)))

(facts "about github-token resolution"
  (fact "uses the static PAT when no App is configured"
    (code/github-token "yetibot" "core") => "pat"
    (provided (code/app-configured?) => false (code/github-pat) => "pat"))

  (fact "mints an installation token when an App is configured"
    (code/github-token "yetibot" "core") => "ghs_installation"
    (provided (code/app-configured?) => true
              (code/installation-token "yetibot" "core") => "ghs_installation")))

(facts "about installation-token"
  (fact "exchanges an app JWT for an installation access token"
    (code/installation-token "yetibot" "core") => "ghs_abc"
    (provided
     (code/app-jwt) => "jwt"
     (code/installation-id "jwt" "yetibot" "core") => 42
     (client/post "https://api.github.com/app/installations/42/access_tokens"
                  anything)
     => {:status 201 :body {:token "ghs_abc"}}))

  (fact "throws when the token exchange fails"
    (code/installation-token "yetibot" "core") => (throws clojure.lang.ExceptionInfo)
    (provided
     (code/app-jwt) => "jwt"
     (code/installation-id "jwt" "yetibot" "core") => 42
     (client/post anything anything) => {:status 401 :body {:message "Bad credentials"}})))

(facts "about code-cmd guards"
  (fact "complains when gemini is not configured"
    (code/code-cmd {:match ["code yetibot/core x" "yetibot/core" "x"]})
    => (contains "Gemini is not configured")
    (provided (code/gemini-key) => nil))

  (fact "complains when no github auth is configured"
    (code/code-cmd {:match ["code yetibot/core x" "yetibot/core" "x"]})
    => (contains "No GitHub auth configured")
    (provided (code/gemini-key) => "key"
              (code/github-auth-configured?) => false))

  (fact "reports the PR url on success"
    (code/code-cmd {:match ["code yetibot/core increase banana budget"
                            "yetibot/core" "increase banana budget"]})
    => (contains "https://github.com/yetibot/core/pull/7")
    (provided (code/gemini-key) => "key"
              (code/github-auth-configured?) => true
              (code/github-token "yetibot" "core") => "tok"
              (code/file-pr anything)
              => {:html_url "https://github.com/yetibot/core/pull/7"}))

  (fact "reports a friendly message when gemini makes no changes"
    (code/code-cmd {:match ["code yetibot/core nothing" "yetibot/core" "nothing"]})
    => (contains "didn't make any changes")
    (provided (code/gemini-key) => "key"
              (code/github-auth-configured?) => true
              (code/github-token "yetibot" "core") => "tok"
              (code/file-pr anything)
              =throws=> (ex-info "Gemini did not make any changes" {:type :no-changes}))))
