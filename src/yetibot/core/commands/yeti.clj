(ns yetibot.core.commands.yeti
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn self-cmd
  "yeti # show known info about Yetibot"
  [_]
  "TODO")

(cmd-hook #"yeti"
  #"self" self-cmd)
