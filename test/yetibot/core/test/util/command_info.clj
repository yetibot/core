(ns yetibot.core.test.util.command-info
  (:require
    [midje.sweet :refer [fact => truthy falsey]]
    [yetibot.core.parser :refer [parser]]
    [yetibot.core.util.command-info :refer :all]
    yetibot.core.commands.collections
    yetibot.core.commands.echo))

(fact
 "simple-command? should return true for a very simple command"
 (simple-command? (parser "echo")) => truthy)

(fact
 "simple-command? should be false on piped commands"
 (simple-command? (parser "echo | echo")) => falsey)

(fact
 "simple-command? should be true on commands with sub-expressions"
 (simple-command? (parser "echo `bar`")) => truthy)

(fact
 "command-execution-info returns partial data on an unknown command"
 (command-execution-info "foo bar")
 => {:parse-tree [:expr [:cmd [:words "foo" [:space " "] "bar"]]]
     :sub-commands nil
     :matched-sub-cmd nil
     :match nil
     :command "foo"
     :command-args "bar"})

(fact
 "command-execution-info runs top level command even when a sub-expr is included"
 (command-execution-info
  "echo foo bar `echo qux`"
  {:run-command? true}) => truthy)

(fact
 "command-execution-info returns correct data for a known command with opts"
 (let [{:keys [result match command-args]}
       (command-execution-info
        "head 2" {:run-command? true
                  :opts ["one" "two" "three"]})]
   match => ["2" "2"]
   result => {:result/value ["one" "two"]}
   command-args => "2"))

