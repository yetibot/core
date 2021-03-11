(ns yetibot.core.test.commands.cmd
  (:require
   [midje.sweet :refer [fact => contains has-prefix just]]
   [matcher-combinators.midje :refer [match]]
   [yetibot.core.midje :refer [value data error]]
   [yetibot.core.commands.cmd :refer [cmd]]
   [yetibot.core.loader :as loader]))

(fact
 "cmd should work as expected"
 (loader/load-commands)
 (cmd {:match "echo hi"}) => "hi")
