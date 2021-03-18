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
      "--interactive")))

(add-some-docs) ; setup

(fact "get-docs-for finds previously added docs for 'git commit'
       and is not empty"
      (h/get-docs-for "git-commit") => not-empty)

(fact "fuzzy-get-docs-for should find the previously added docs
       for 'grep' using 'greo' as input"
      (first (h/fuzzy-get-docs-for "greo")) => (contains "grep"))

(deftest similar
  (let [similar (map #(str "foobar" %) (range 4))]
    (doall (map #(h/add-docs % ["foobar baz"]) similar))
    (is
      (re-find #"Did.you.mean" (str (first (h/fuzzy-get-docs-for "foobar"))))
      "When there are many similar matches, show them to the user")))
