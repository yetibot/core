(ns yetibot.core.commands.echo
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn echo-cmd
  "echo <text> # Echos back <text>. Useful for piping."
  {:yb/cat #{:util}}
  [{:keys [args] :as cmd-args}]
  (info "echo cmd args:" cmd-args)
  args)

(cmd-hook #"echo"
          _ echo-cmd)
