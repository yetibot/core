(ns yetibot.core.commands.nil
  (:require
    [clojure.string :as s]
    [yetibot.core.chat :refer [suppress]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn nil-cmd
  "nil # eats all passed args; equivalent to writing to /dev/null"
  {:yb/cat #{:util}}
  [_]
  (suppress {}))

(cmd-hook #"nil"
          _ nil-cmd)
