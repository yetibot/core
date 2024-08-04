(ns yetibot.core.commands.echo
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn echo-cmd
  "echo <text> # Echos back <text> as a string. Useful for piping."
  {:yb/cat #{:util}}
  [{:keys [args] :as cmd-args}]
  {:result/value (str args)
   :result/data cmd-args})

(cmd-hook #"echo"
  _ echo-cmd)
