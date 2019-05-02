(ns yetibot.core.test.commands.karma
  (:require
   [midje.sweet :refer [fact =>]]
   [yetibot.core.midje :refer [value data]]
   [yetibot.core.commands.date :refer :all]))

(fact "date command parses natural language dates"
  (date-cmd {:match "the first day of the year 2000"}) =>
      (value "2000-01-01T00:00:00.000-07:00"))
