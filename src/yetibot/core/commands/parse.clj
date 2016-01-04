(ns yetibot.core.commands.parse
  (:require
    [yetibot.core.parser :refer [parser]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn parse-cmd
  "parse <text> # parse <text> into an AST using yetibot's internal parser"
  {:yb/cat #{:util}}
  [{match :match}]
  (-> match parser str))

(cmd-hook #"parse"
  _ parse-cmd)
