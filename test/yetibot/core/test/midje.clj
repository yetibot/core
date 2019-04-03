(ns yetibot.core.test.midje
  (:require
   [yetibot.core.midje :refer :all]
   [midje.sweet :refer [fact => =not=>]]))

(def test-str "Quis custodiet ipsos custodes?")
(def test-map {:result/value test-str})

(fact "Simple string equality works"
  test-map     => (value test-str)
  test-str =not=> (value test-str))

(fact "Midje's 'Extended Equality' works, as verified by a regex"
  test-str     => #"custodes"
  test-str =not=> #"fidem"
  test-map     => (value #"custodes")
  test-map =not=> (value #"fidem"))

(fact "Midje's 'Extended Equality' works, as verified by a predicate"
  test-str     => string?
  test-map =not=> string?
  test-map     => (value string?)
  test-str =not=> (value string?))
