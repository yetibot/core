(ns yetibot.core.test.models.help
  (:require [yetibot.core.models.help :as h]
            [clojure.test :refer :all]
            [midje.sweet :refer [=> fact contains]]))

(defn add-some-docs []
  (h/add-docs
    "grep"
    ["grep -A <foo> # do something"
     "grep -B <bar> # wow"])
  (h/add-docs
    "git-commit"
    '("-a # all"
      "--amend # amend previous commit"
      "-m <msg>"
      "--interactive"))
  (let [similar (map #(str "foobar" %) (range 4))]
    (doseq [s similar] (h/add-docs s ["foobar baz"]))))

(add-some-docs) ; setup

(fact "get-docs-for finds previously added docs for 'git commit'
       and is not empty"
      (h/get-docs-for "git-commit") => not-empty)

(fact "fuzzy-get-docs-for should find the previously added docs
       for 'grep' using 'greo' as input"
      (first (h/fuzzy-get-docs-for "greo")) => (contains "grep"))

(fact "fuzzy-get-docs-for should prompt user with similar commands
       when input command matches many similar items"
      (first (h/fuzzy-get-docs-for "foobar")) => (contains "Did you mean"))

(fact "remove-docs should remove a previously added doc
       from the help store"
      (h/add-docs "removeme" ["i am going to remove this soon"])
      ;; prove it exists 1st
      (h/get-docs-for "removeme") => not-empty
      (h/remove-docs "removeme")
      (h/get-docs-for "removeme") => empty?)
