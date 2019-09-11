(ns yetibot.core.test.handler
  (:require
   yetibot.core.commands.echo
   yetibot.core.commands.help
   yetibot.core.commands.category
   yetibot.core.commands.collections
   yetibot.core.commands.render

   [midje.sweet :refer [fact facts =>]]
   [yetibot.core.parser :refer [parse-and-eval parser]]
   [yetibot.core.handler :refer :all]
   [yetibot.core.commands.history]
   [yetibot.core.util.command :refer [extract-command]]
   [clojure.string :as s]
   [yetibot.core.repl :refer [load-minimal]]))

(load-minimal)

(comment
  ;; generate some history
  (dotimes [i 10]
    (handle-raw
     {:adapter :test :room "foo"}
     {:id "yetitest"}
     :message
     {:username "yetibot" :id "123"}
     {:body (str "test history: " i)})))

(def multiline-str (s/join \newline [1 2 3]))

(fact
 "Newlines are preserved in command handling"
 (:value (handle-unparsed-expr (str "echo " multiline-str))) => multiline-str)

(fact
 "Extracting commands allows specifying a prefix"
 (let [prefix "?"
       body (str prefix "command arg1 arg2")]
   (extract-command body prefix) => [body (subs body 1)]))

(fact
 "Nothing is extracted from a potential command if the prefix does not match"
 (let [prefix "?"
       body "|command arg1 arg2"]
   (extract-command body prefix) => nil?))

(fact
 "Commands can be piped in succession"
 (:value (parse-and-eval "echo there | echo `echo hi`")) =>
 "hi there")
