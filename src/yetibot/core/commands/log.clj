(ns yetibot.core.commands.log
  (:require
    [yetibot.core.db.log :as log]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn- fmt-log
  [{:keys [prefix level message]}]
  (format "%s %s %s" prefix (name level) message))

(defn less-log
  "log less # retrieve log messages only for all time"
  [_]
  (map :message (log/find-all)))

(defn log-cmd
  "log # retrieve the full yetibot log for all time"
  [_]
  (map fmt-log (log/find-all)))

(cmd-hook #"log"
          #"less" less-log
          _ log-cmd)
