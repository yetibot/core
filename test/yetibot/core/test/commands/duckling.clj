(ns yetibot.core.test.commands.duckling
  (:require
   [midje.sweet :refer [fact =>]]
   [yetibot.core.midje :refer [value data error]]
   [yetibot.core.commands.duckling :refer :all]))

(fact "date command parses natural language dates"
  (date-cmd {:match "the first day of the year 2000"}) =>
      (value "2000-01-01T00:00:00.000-07:00"))

(fact "date returns error if it can't find a date"
  (date-cmd
    {:match "do you even"}) => (error "do you even doesn't look like a date"))

(def two-dollars-data [{:dim :number,
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
                         :value "2002-01-01T00:00:00.000-07:00",
                         :grain :year,
                         :values []},
                        :start 0,
                        :end 3,
                        :latent true}])

(fact "duckling command parses lots of stuff"
  (duckling-cmd
    {:match "two dollars"}) => (data two-dollars-data))

(fact "duckling command provides some simple formatting"
  (duckling-cmd
    {:match "two dollars"}) => (value ["Number: 2"
                                       "Amount of money: 2"
                                       "Distance: 2"
                                       "Volume: 2"
                                       "Temperature: 2"
                                       "Time: 2002-01-01T00:00:00.000-07:00"]))
