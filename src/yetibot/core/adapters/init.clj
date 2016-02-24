(ns yetibot.core.adapters.init
  "Manages the lifecycle of adapters"
  (:require
    [schema.core :as s]
    [yetibot.core.adapters.slack :as slack]
    [yetibot.core.adapters.irc :as irc]
    [taoensso.timbre :as log :refer [info warn error]]
    [taoensso.timbre :as log :refer [info warn error]]
    [clojure.stacktrace :refer [print-stack-trace]]
    [yetibot.core.adapters.adapter :as a]
    [yetibot.core.config :refer [get-config conf-valid?]]
    [yetibot.core.adapters.irc :as irc]))

(def adapters-schema
  [{:name s/Str
    :type s/Str
    ;; different types of adapters require different keys - make them optional
    ;; Slack
    (s/optional-key :token) s/Str
    ;; IRC
    (s/optional-key :host) s/Str
    (s/optional-key :port) s/Str
    (s/optional-key :username) s/Str
    (s/optional-key :password) s/Str}])

(defn adapters-config []
  (let [c (get-config adapters-schema [:yetibot :adapters])]
    (if (:error c)
      (throw (ex-info "Invalid adapters config" c))
      (:value c))))

(defn report-ex [f n]
  (try
    (info "Trying" n)
    (f)
    (catch Exception e
      (warn "Error on" n (with-out-str (print-stack-trace e))))))

(defn make-adapter [idx config]
  (condp = (keyword (:type config))
    :slack (slack/make-slack idx config)
    :irc (irc/make-irc idx config)
    (throw (ex-info (str "Unknown adapter type " (:type config)) config))))

(defn validate-adapter-config!
  "Logs an error if this adapter config does not contain :name and :type keys."
  [config]
  (when-not (:type config)
    (throw (ex-info ":type is required" {:config config})))
  (when-not (:name config)
    (throw (ex-info ":name is required" {:config config}))))

(defn register-adapters! []
  (dorun
    (map-indexed
      (fn [idx adapter-config]
        (validate-adapter-config! adapter-config)
        (a/register-adapter!
          (make-adapter idx adapter-config)
          (:name adapter-config)))
      (adapters-config))))

(defn start-adapters! []
  (dorun
    (map (fn [adapter]
           (report-ex #(a/start adapter) (a/platform-name adapter)))
         (a/active-adapters))))

(defn start []
  (report-ex register-adapters! "Register adapters")
  (info "Registered" (count (a/active-adapters)) "adapters")
  (future (start-adapters!)))

(defn stop []
  (dorun (map #(a/stop %) (a/active-adapters))))
