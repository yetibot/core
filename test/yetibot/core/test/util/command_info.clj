(ns yetibot.core.test.util.command-info
  (:require [midje.sweet :refer [fact => truthy falsey]]
            [yetibot.core.parser :refer [parser]]
            [yetibot.core.util.command-info :as ci]
            yetibot.core.commands.echo))

(fact
 "simple-command? should return true for a very simple command"
 (ci/simple-command? (parser "echo")) => truthy)

(fact
 "simple-command? should be false on piped commands"
 (ci/simple-command? (parser "echo | echo")) => falsey)

(fact
 "simple-command? should be true on commands with sub-expressions"
 (ci/simple-command? (parser "echo `bar`")) => truthy)

(fact
 "command-execution-info returns partial data on an unknown command"
 (ci/command-execution-info "foo bar")
 => {:parse-tree [:expr [:cmd [:words "foo" [:space " "] "bar"]]]
     :sub-commands nil
     :matched-sub-cmd nil
     :match nil
     :command "foo"
     :command-args "bar"})

(fact
 "command-execution-info runs top level command even when a sub-expr is included"
 (ci/command-execution-info
  "echo foo bar `echo qux`"
  {:run-command? true}) => truthy)
