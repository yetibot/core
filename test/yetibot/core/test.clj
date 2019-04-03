(ns yetibot.core.test
  (:require
   [midje.sweet :refer [defchecker checker fact => =not=>]]
   [midje.checking.core :refer [extended-=]]))

;; A few Midje checkers providing Extended Equality tests of standard
;; keys in the map returned by commands supporting piped data.  These
;; are intended to be used in *.command.* tests.

(defn mk-checker
  [expected key]
  (checker
   [actual]
   (extended-= (key actual) expected)))

(defchecker value [expected] (mk-checker expected :result/value))
(defchecker data  [expected] (mk-checker expected :result/data))
(defchecker error [expected] (mk-checker expected :result/error))

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
