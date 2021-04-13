(ns yetibot.core.adapters
  "Manages the lifecycle of adapters"
  (:require
   [clojure.spec.alpha :as s]
   [yetibot.core.adapters.irc :as irc]
   [yetibot.core.adapters.slack :as slack]
   [yetibot.core.adapters.mattermost :as mattermost]
   [yetibot.core.adapters.web :as web]
   [taoensso.timbre :as log :refer [info debug warn]]
   [clojure.stacktrace :refer [print-stack-trace]]
   [yetibot.core.adapters.adapter :as a]
   [yetibot.core.config :refer [get-config]]))

(s/def ::adapter (s/or :web ::web/config
                       :slack ::slack/config
                       :irc ::irc/config
                       :mattermost ::mattermost/config))

(s/def ::config (s/map-of keyword? ::adapter))

(def web-adapter-config
  "The default Adapter is Web. It runs a web server where users can monitor the
   state of Yetibot and run comands in its web-based REPL."
  {:yetiweb {:type "web"}})

(defn adapters-config []
  (let [c (get-config ::config [:adapters])]
    (when (:error c)
      (warn "Invalid adapters config, launching default web adapter" c))
    (merge web-adapter-config (:value c))))

(comment
  (adapters-config))

(defn report-ex [f n]
  (try
    (info "Trying" n)
    (f)
    (catch Exception e
      (warn "Error on" n (with-out-str (print-stack-trace e))))))

(defn make-adapter [config]
  (condp = (keyword (:type config))
    :web (web/make-web config)
    :slack (slack/make-slack config)
    :irc (irc/make-irc config)
    :mattermost (mattermost/make-mattermost config)
    (throw (ex-info (str "Unknown adapter type " (:type config)) config))))

(defn register-adapters!
  "Registers all config'ed adapters"
  []
  (run!
   (fn [[uuid adapter-config]]
     (let [adapter-config (assoc adapter-config :name uuid)]
       (debug "Registering" (pr-str adapter-config))
       (a/register-adapter! uuid (make-adapter adapter-config))))
   (adapters-config)))

(defn start-adapters!
  "Starts all config'ed adapters"
  []
  (run!
   (fn [adapter]
     (report-ex #(a/start adapter) (a/platform-name adapter)))
   (a/active-adapters)))


(defn start []
  (report-ex register-adapters! "Register adapters")
  (info "Registered" (count (a/active-adapters)) "adapters")
  (future (start-adapters!)))

(comment
  (start)

  (a/active-adapters)

  (map
   (fn [[uuid adapter-config]]
     (let [adapter-config (assoc adapter-config :name uuid)]
       (debug "Registering" \newline (pr-str adapter-config))))
   (adapters-config)))

(defn stop
  "Stops active adapters"
  []
  (run! #(a/stop %) (a/active-adapters))
  (reset! a/adapters {}))

(comment
  (type (first (a/active-adapters)))

  (type (last (a/active-adapters)))

  (a/platform-name (first (a/active-adapters)))
  (a/platform-name (last (a/active-adapters)))

  (a/stop (first (a/active-adapters)))
  (register-adapters!)
  (adapters-config))
