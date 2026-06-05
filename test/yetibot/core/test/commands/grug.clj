(ns yetibot.core.test.commands.grug
  (:require
   [midje.sweet :refer [fact => contains]]
   [yetibot.core.commands.grug :refer [grug-cmd quotes]]))

(fact "grug command returns a random quote from the quote list"
      (let [result (grug-cmd {})]
        quotes => (contains result)))
