(ns yetibot.core.adapters
  "Manages the lifecycle of adapters"
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.adapters.irc :as irc]
    [yetibot.core.adapters.slack :as slack]
    [taoensso.timbre :as log :refer [info debug warn error]]
    [clojure.stacktrace :refer [print-stack-trace]]
    [yetibot.core.adapters.adapter :as a]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.adapters.irc :as irc]))

(s/def ::adapter (s/or :slack ::slack/config
                       :irc ::irc/config))

(s/def ::config (s/map-of keyword? ::adapter))

(defn adapters-config []
  (let [c (get-config ::config [:adapters])]
    (if (:error c)
      (throw (ex-info "Invalid adapters config" c))
      (:value c))))

(defn report-ex [f n]
  (try
    (info "Trying" n)
    (f)
    (catch Exception e
      (warn "Error on" n (with-out-str (print-stack-trace e))))))

(defn make-adapter [config]
  (condp = (keyword (:type config))
    :slack (slack/make-slack config)
    :irc (irc/make-irc config)
    (throw (ex-info (str "Unknown adapter type " (:type config)) config))))

(defn register-adapters! []
  (dorun
    (map
      (fn [[uuid adapter-config]]
        (let [adapter-config (assoc adapter-config :name uuid)]
          (debug "Registering" (pr-str adapter-config))
          (a/register-adapter! uuid (make-adapter adapter-config))))
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
  (dorun (map a/stop (a/active-adapters)))
  (reset! a/adapters {}))
