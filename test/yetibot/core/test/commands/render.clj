(ns yetibot.core.test.commands.render
  (:require
    [midje.sweet :refer [fact => contains has-prefix just]]
    [yetibot.core.midje :refer [value data error]]
    [yetibot.core.commands.render :refer :all]
    [yetibot.core.util.command-info :refer [command-execution-info]]))

(def execution-opts {:run-command? true
                     :data {:adj "lazy" :noun "water buffalo"}})

(fact
 "a simple template works"
 (:result
  (command-execution-info "render the {{adj}} brown {{noun}}" execution-opts))
 => (value "the lazy brown water buffalo"))

(fact
 "A template operating over a sequential produces a sequential"
 (:result (command-execution-info "render item {{.}}"
                                  (assoc execution-opts
                                         :data [1 2]
                                         :raw [1 2])))
 => (value ["item 1" "item 2"]))

(fact
 "An invalid template that throws an error"
 (:result (command-execution-info
           "render the {{adj|lol}} brown {{noun}}"
           execution-opts))
 (error  "No filter defined with the name 'lol'"))

(fact
 "Some cool selmer filters"
 (:result (command-execution-info
           "render the {{adj|upper}} brown {{noun|capitalize}}"
           execution-opts))
 => (value "the LAZY brown Water buffalo"))
