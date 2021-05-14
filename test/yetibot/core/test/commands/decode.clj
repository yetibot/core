(ns yetibot.core.test.commands.decode
  (:require [yetibot.core.util.command-info :refer [command-execution-info]]
            [midje.sweet :refer [=> fact facts]]
            [yetibot.core.loader :as ldr]))

(facts
 "about decode"
 (ldr/load-commands)
 (fact
  "HTML encoded strings get decoded"
  (-> (command-execution-info
       "decode Come check out Trevor Hartman&#39;s talk &quot;Growing a Chatops Platform and Having Fun with Clojure&quot; where we take a look at the development of Yetibot!"
       {:run-command? true})
      :result
      :result/value) => "Come check out Trevor Hartman's talk \"Growing a Chatops Platform and Having Fun with Clojure\" where we take a look at the development of Yetibot!"))
