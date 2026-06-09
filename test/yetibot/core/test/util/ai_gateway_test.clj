(ns yetibot.core.test.util.ai-gateway-test
  (:require
   [midje.sweet :refer [fact facts => throws provided anything]]
   [midje.checkers :refer [checker]]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [yetibot.core.util.ai-gateway :as ai-gateway]))

(facts "about configured?"
  (fact "true when account, gateway, and key are all set"
    (ai-gateway/configured?) => true
    (provided (#'ai-gateway/account-id) => "acct"
              (#'ai-gateway/gateway-id) => "gw"
              (#'ai-gateway/api-key) => "sk-test"))
  (fact "false when the key is missing"
    (ai-gateway/configured?) => false
    (provided (#'ai-gateway/account-id) => "acct"
              (#'ai-gateway/gateway-id) => "gw"
              (#'ai-gateway/api-key) => "")))

(facts "about endpoint + model routing"
  (fact "endpoint is the gateway's OpenAI-compat chat completions url"
    (#'ai-gateway/endpoint) => "https://gateway.ai.cloudflare.com/v1/acct/gw/compat/chat/completions"
    (provided (#'ai-gateway/account-id) => "acct"
              (#'ai-gateway/gateway-id) => "gw"))
  (fact "the model is prefixed with the provider route"
    (#'ai-gateway/qualified-model "kimi-k2.5") => "custom-moonshot/kimi-k2.5"
    (provided (#'ai-gateway/provider) => "custom-moonshot"))
  (fact "a blank provider leaves the model untouched"
    (#'ai-gateway/qualified-model "openai/gpt-5") => "openai/gpt-5"
    (provided (#'ai-gateway/provider) => "")))

(def ^:private request-ok?
  "Checker for the clj-http opts: bearer auth + provider-qualified model in body."
  (checker [opts]
    (and (= "Bearer sk-test" (get-in opts [:headers "Authorization"]))
         (= "custom-moonshot/kimi-k2.5"
            (-> (:body opts) (json/read-str :key-fn keyword) :model)))))

(facts "about chat"
  (fact "posts to the compat endpoint with bearer auth + qualified model, returning trimmed text + usage"
    (ai-gateway/chat {:model "kimi-k2.5" :messages [{:role "user" :content "hi"}]})
    => {:text "hello there" :usage {:total_tokens 5}}
    (provided
      (#'ai-gateway/account-id) => "acct"
      (#'ai-gateway/gateway-id) => "gw"
      (#'ai-gateway/provider) => "custom-moonshot"
      (#'ai-gateway/api-key) => "sk-test"
      (#'ai-gateway/auth-token) => nil
      (client/post "https://gateway.ai.cloudflare.com/v1/acct/gw/compat/chat/completions"
                   request-ok?)
      => {:status 200
          :body {:choices [{:message {:content "  hello there  "}}]
                 :usage {:total_tokens 5}}}))

  (fact "throws on a non-2xx response"
    (ai-gateway/chat {:model "kimi-k2.5" :messages []})
    => (throws clojure.lang.ExceptionInfo)
    (provided
      (#'ai-gateway/account-id) => "acct"
      (#'ai-gateway/gateway-id) => "gw"
      (#'ai-gateway/provider) => "custom-moonshot"
      (#'ai-gateway/api-key) => "sk-test"
      (#'ai-gateway/auth-token) => nil
      (client/post anything anything) => {:status 500 :body {:error "boom"}})))
