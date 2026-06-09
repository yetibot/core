(ns yetibot.core.util.ai-gateway
  "OpenAI-compatible chat completions routed through a Cloudflare AI Gateway.

   The gateway fronts any upstream provider (Moonshot/Kimi by default) so every
   model call is observable and cost-controlled in one place. Settings live under
   [:cloudflare :ai-gateway]:

     :account-id  Cloudflare account id
     :gateway-id  the AI Gateway's slug
     :provider    gateway route / custom-provider prefix (default \"custom-moonshot\")
     :api-key     the upstream provider key, sent as the Bearer token
     :auth-token  optional cf-aig-authorization token (Authenticated Gateway)"
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [taoensso.timbre :refer [error]]
            [yetibot.core.config :refer [get-config]]))

(defn- cfg [k] (:value (get-config string? [:cloudflare :ai-gateway k])))

(defn- account-id [] (cfg :account-id))
(defn- gateway-id [] (cfg :gateway-id))
(defn- provider [] (or (cfg :provider) "custom-moonshot"))
(defn- api-key [] (cfg :api-key))
(defn- auth-token [] (cfg :auth-token))

(defn configured? []
  (boolean (and (not (string/blank? (account-id)))
                (not (string/blank? (gateway-id)))
                (not (string/blank? (api-key))))))

(defn- endpoint []
  (format "https://gateway.ai.cloudflare.com/v1/%s/%s/compat/chat/completions"
          (account-id) (gateway-id)))

(defn- qualified-model
  "Prefix the model with the gateway provider route (e.g. kimi-k2.5 ->
   custom-moonshot/kimi-k2.5) so the unified endpoint knows where to route."
  [model]
  (let [p (provider)]
    (if (string/blank? p) model (str p "/" model))))

(defn- redact [s]
  (some-> s (string/replace #"(?i)(Bearer\s+)[\w._-]+" "$1***")))

(defn chat
  "POST a chat completion through the gateway. `messages` is a vector of
   {:role .. :content ..}. Returns {:text <assistant content> :usage <map>}.
   Throws ex-info on a non-2xx response; clj-http surfaces transport timeouts."
  [{:keys [model messages timeout-ms]}]
  (let [headers (cond-> {"Authorization" (str "Bearer " (api-key))}
                  (not (string/blank? (auth-token)))
                  (assoc "cf-aig-authorization" (str "Bearer " (auth-token))))
        resp (client/post (endpoint)
                          {:headers headers
                           :content-type :json
                           :body (json/write-str {:model (qualified-model model)
                                                  :messages messages}
                                                 :escape-slash false)
                           :as :json
                           :connection-timeout 10000
                           :socket-timeout (or timeout-ms 120000)
                           :throw-exceptions false})
        status (:status resp)]
    (when-not (<= 200 status 299)
      (let [msg (redact (str (:body resp)))]
        (error "ai-gateway: error" status "-" msg)
        (throw (ex-info (str "AI gateway error (" status "): " msg)
                        {:type :ai-gateway-error :status status}))))
    {:text (some-> (get-in resp [:body :choices 0 :message :content]) string/trim)
     :usage (get-in resp [:body :usage])}))
