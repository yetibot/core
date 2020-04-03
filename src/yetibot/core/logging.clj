(ns yetibot.core.logging
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-config]]
    [taoensso.timbre.appenders.3rd-party.rolling :refer [rolling-appender]]
    [taoensso.timbre.appenders.core :refer [println-appender]]
    [taoensso.timbre
     :as timbre
     :refer [trace debug info warn error fatal spy with-log-level]]))

(s/def ::log-level-config string?)

(defn log-level []
  (let [v (get-config ::log-level-config [:log :level])]
    (if (:error v)
      ;; default config level
      :info
      (keyword (:value v)))))

(s/def ::rolling-appender-enabled-config string?)

(defn rolling-appender-enabled?
  "Rolling appender is enabled by default. Disable it by setting enabled=false"
  []
  (let [v (get-config ::rolling-appender-enabled-config
                      [:log :rolling :enabled])]
    (if-let [enabled (:value v)]
      (not= enabled "false")
      true)))

(s/def ::log-file-path string?)

(defn start []
  (timbre/set-config!
    {:level (log-level)
     :appenders
     ;; stdout
     {:println (println-appender {:stream :auto})
      ;; rolling log files
      :rolling-appender (rolling-appender
                          {:enabled? (rolling-appender-enabled?)
                           :path (get-config ::log-file-path [:log :path])
                           :pattern :daily})}}))
