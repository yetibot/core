(ns yetibot.core.commands.error
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn error-cmd
  "error <message> # Generates an error that short-circuits an expression pipeline"
  {:yb/cat #{:util}}
  [{:keys [args] :as cmd-args}]
  {:result/error args})

(cmd-hook #"error"
  _ error-cmd)
