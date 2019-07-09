(ns yetibot.core.midje
  (:require
   [midje.sweet :refer [defchecker checker]]
   [midje.checking.core :refer [extended-=]]))

;; A few Midje checkers providing Extended Equality tests of standard
;; keys in the map returned by commands supporting piped data.  These
;; are intended to be used in *.command.* tests.

(defn mk-checker
  [expected key]
  (checker
   [actual]
   (pr-str actual)
   (extended-= (key actual) expected)))

(defchecker value [expected] (mk-checker expected :result/value))
(defchecker data  [expected] (mk-checker expected :result/data))
(defchecker error [expected] (mk-checker expected :result/error))
