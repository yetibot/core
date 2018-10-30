(ns yetibot.core.commands.parse
  (:require
    [yetibot.core.util.format :refer [remove-surrounding-quotes]]
    [clojure.pprint :refer [pprint *print-right-margin*]]
    [yetibot.core.parser :refer [parser]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn parse-cmd
  "parse <text> # parse <text> into an AST using yetibot's internal parser"
  {:yb/cat #{:util}}
  [{match :match}]
  (let [parsed (parser (remove-surrounding-quotes match))]
    (binding [*print-right-margin* 80]
      {:result/value (with-out-str (pprint parsed))
       :result/data parsed})))

(cmd-hook #"parse"
  _ parse-cmd)
