(ns yetibot.core.unparser
  (:require
   [clojure.string :refer [join]]
   [instaparse.core :as insta]))

(defn reconstruct-pipe [cmds] (join " | " cmds))

(def unparse-transformer
  "Takes a parse tree and turns it back into a string"
  (partial
    insta/transform
    {:words str
     :literal str
     :space str
     :parened str
     :cmd identity
     ;; avoid wrapping the top level :expr in a subexpression when unparsing
     :sub-expr str
     :expr (fn [& cmds] (str "$(" (reconstruct-pipe cmds)  ")"))
     }))

(comment
  (unparse-transformer
   [[:cmd [:words "grep" [:space " "] "minutes"]]]))

(defn unparse [parse-tree]
  (let [cmds (rest parse-tree) ]
    (reconstruct-pipe (unparse-transformer cmds))))
