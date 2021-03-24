(ns yetibot.core.test.handler
  (:require
   yetibot.core.commands.echo
   yetibot.core.commands.help
   yetibot.core.commands.category
   yetibot.core.commands.collections
   yetibot.core.commands.render
   yetibot.core.commands.history

   [midje.sweet :refer [fact facts =>]]
   [yetibot.core.parser :refer [parse-and-eval]]
   [yetibot.core.handler :refer [handle-raw handle-unparsed-expr]]
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

(fact
 "Commands with data work as expected"
 (:data (parse-and-eval "category names")) =>
 {:async "commands that execute asynchronously",
  :broken
  "known to be broken, probably due to an API that disappeared",
  :chart "returns a chart of some kind",
  :ci "continuous integration",
  :collection "operates on collections",
  :crude
  "may return crude, racy and potentially NSFW results (e.g. urban)",
  :fun "generally fun and not work-related",
  :gif "returns a gif",
  :img "returns an image url",
  :info "information lookups (e.g. wiki, wolfram, weather)",
  :infra "infrastructure automation",
  :issue "issue tracker",
  :meme "returns a meme",
  :repl "language REPLs",
  :util
  "utilities that help transform expressions or operate Yetibot"})

(facts
 "Sub expressions can access the data propagated from the previous pipe"
 ;;
 (:value (parse-and-eval
          "category names | echo async: `render {{async}}`")) =>
 "async: commands that execute asynchronously"
 ;; slightly more compleex
 (:value
  (parse-and-eval
   "category names | echo async: `render {{async}}` ci: `render {{ci}}`")) =>
 "async: commands that execute asynchronously ci: continuous integration")

(fact
 "Commands with literals can be transformed"
 (:value (parse-and-eval "echo \"hi\"")) => "\"hi\"")
