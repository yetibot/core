(ns yetibot.core.test.commands.duckling
  (:require
   [midje.sweet :refer [fact => has-prefix just]]
   [matcher-combinators.midje :refer [match]]
   [yetibot.core.midje :refer [value data error]]
   [yetibot.core.commands.duckling :refer :all]))

;; Note - we use `match` and `has-prefix` to check date strings irrespetive of
;; time zones

(fact "date command parses natural language dates"
  (date-cmd {:match "the first day of the year 2000"}) =>
      (value (has-prefix "2000-01-01T00:00:00.000")))

(fact "date returns error if it can't find a date"
  (date-cmd
    {:match "do you even"}) => (error "do you even doesn't look like a date"))

(fact "duckling command parses lots of stuff"
  (duckling-cmd {:match "two dollars"}) =>
  (data
   ;; allows nested `has-prefix` usage, see:
   ;; https://github.com/marick/Midje/issues/418#issuecomment-368218819
   (match
    [{:dim :number,
      :body "two",
      :value {:type "value", :value 2},
      :start 0,
      :end 3}
     {:dim :amount-of-money,
      :body "two dollars",
      :value {:type "value", :value 2, :unit "$"},
      :start 0,
      :end 11}
     {:dim :distance,
      :body "two",
      :value {:type "value", :value 2},
      :start 0,
      :end 3,
      :latent true}
     {:dim :volume,
      :body "two",
      :value {:type "value", :value 2},
      :start 0,
      :end 3,
      :latent true}
     {:dim :temperature,
      :body "two",
      :value {:type "value", :value 2},
      :start 0,
      :end 3,
      :latent true}
     {:dim :time,
      :body "two",
      :value
      {:type "value",
       :value (has-prefix
               "2002-01-01T00:00:00.000"),
       :grain :year,
       :values []},
      :start 0,
      :end 3,
      :latent true}])))

(fact "duckling command provides some simple formatting"
      (duckling-cmd
       {:match "two dollars"}) =>
      (value (match ["Number: 2"
                     "Amount of money: 2"
                     "Distance: 2"
                     "Volume: 2"
                     "Temperature: 2"
                     (has-prefix "Time: 2002-01-01T00:00:00.000")])))
