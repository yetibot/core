(ns yetibot.core.test.commands.cmd
  (:require
   [midje.sweet :refer [fact =>]]
   [yetibot.core.commands.cmd :refer [cmd]]
   yetibot.core.test.db
   yetibot.core.commands.echo))

(fact "cmd should work as expected"
      (cmd {:match "echo hi"}) => "hi")
