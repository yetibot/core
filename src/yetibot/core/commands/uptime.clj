(ns yetibot.core.commands.uptime
  (:require [yetibot.core.hooks :refer [cmd-hook]])
  (:import org.apache.commons.lang3.time.DurationFormatUtils))

(defonce start-time (System/currentTimeMillis))

(defn now [] (System/currentTimeMillis))

(defn uptime-millis [] (- (now) start-time))

(defn uptime-cmd
  "uptime # list uptime in milliseconds"
  {:yb/cat #{:util}}
  [_] (DurationFormatUtils/formatDurationWords (uptime-millis) true true))

(cmd-hook #"uptime"
          _ uptime-cmd)
