(ns yetibot.core.test.commands.collections
  (:require
   [yetibot.core.commands.collections :as colls]
   [midje.sweet :refer [fact facts =>]]))

(facts "slide-context"
  (fact "gives the context" (colls/slide-context (range 10) 3 2) => [1 2 3 4 5])
  (fact "is shorter if there aren't enough results before"
        (colls/slide-context (range 10) 1 2) => [0 1 2 3])
  (fact "is shorter if there aren't enough results at the end"
        (colls/slide-context (range 10) 9 3) => [6 7 8 9]))

(facts "sliding-filter"
  (fact (colls/sliding-filter 1 #(> % 4) (range 10)) =>
        [[4 5 6] [5 6 7] [6 7 8] [7 8 9] [8 9]])
  (fact (colls/sliding-filter 1 odd? (range 6 10)) =>
        [[6 7 8] [8 9]]))

(facts "grep context"
  (fact "for multiple matches"
        (colls/grep-data-structure #"yes"
                                   (map-indexed vector
                                                ["devth: foo"
                                                 "devth: yes"
                                                 "devth: bar"
                                                 "devth: lol"
                                                 "devth: ok"
                                                 "devth: baz"
                                                 "devth: !history | grep -C 2 yes"])
                                   {:context 2})
        =>
        [[0 "devth: foo"]
         [1 "devth: yes"]
         [2 "devth: bar"]
         [3 "devth: lol"]
         [4 "devth: ok"]
         [5 "devth: baz"]
         [6 "devth: !history | grep -C 2 yes"]])
  (fact "for single match"
        (colls/grep-data-structure #"foo"
                                   (map-indexed vector
                                                ["bar" "lol" "foo" "baz" "qux"])
                                   {:context 1})
        =>
        '([1 "lol"] [2 "foo"] [3 "baz"]))
  (fact "no overlapping matches"
        (colls/grep-data-structure #"foo"
                                   (map-indexed vector
                                                ["foo" "bar" "baz" "foo"])
                                   {:context 2})
        =>
        [[0 "foo"]
         [1 "bar"]
         [2 "baz"]
         [3 "foo"]]))

(facts
 "grep-cmd-test"
 (fact "gives full match"
       (colls/grep-cmd {:match "foo" :opts ["foo" "bar"]})
       =>
       #:result{:value ["foo"] :data nil})
 (fact "gives partial match"
       (colls/grep-cmd {:match "foo" :opts ["foobar" "baz"]})
       =>
       #:result{:value ["foobar"] :data nil})
 (fact "with -C flag gives context"
       (colls/grep-surrounding {:match (re-find #"-C\s+(\d+)\s+(.+)" "-C 1 baz")
                                :opts ["foo" "bar" "baz"]})
       =>
       #:result{:value ["bar" "baz"], :data nil})
 (fact "with -v flag gives inverted match"
       (colls/inverted-grep {:match (re-find #"-v\s+(.+)" "-v bar")
                             :opts ["foo" "bar" "baz"]})
       =>
       #:result{:value ["foo" "baz"], :data nil}))

(facts
 "about flatten-cmd"
 (let [cmn-res ["1" "2" "3"]]
   (fact
    "simple case using vector"
    (:result/value (colls/flatten-cmd {:opts ["1" "2" "3"]}))
    => cmn-res)
   (fact
    "simple case using nested vector"
    (:result/value (colls/flatten-cmd {:opts [["1" "2" "3"]]}))
    => cmn-res)
   (fact
    "case using single str w/ newlines in vector"
    (:result/value (colls/flatten-cmd
                    {:opts [(str 1 \newline 2 \newline 3 \newline)]}))
    => cmn-res)
   (fact
    "case using single str w/ newlines in nested vector"
    (:result/value (colls/flatten-cmd
                    {:opts [[[(str 1 \newline 2 \newline 3 \newline)]]]}))
    => cmn-res)))
