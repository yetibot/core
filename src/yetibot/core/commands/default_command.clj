(ns yetibot.core.commands.default-command
  "The default, configurable fallback command when no other command matches. Any
   args will be appended to the end of the command."
  (:require
    [yetibot.core.models.default-command :refer [configured-default-command]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn show-default-command
  "default command # show the configured default fallback command"
  [_]
  (configured-default-command))

(cmd-hook #"default"
  #"command" show-default-command)
