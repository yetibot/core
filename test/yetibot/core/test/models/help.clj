(ns yetibot.core.test.models.help
  (:require
    [yetibot.core.models.help :refer :all]
    [clojure.test :refer :all]))

(defn add-some-docs []
  (add-docs
    "grep"
    ["grep -A <foo> # do something"
     "grep -B <bar> # wow"])
  (add-docs
    "git-commit"
    '("-a # all"
      "--amend # amend previous commit"
      "-m <msg>"
      "--interactive")))

(add-some-docs) ; setup

(deftest add-and-retrieve-docs
  (is
    (not (empty? (get-docs-for "git-commit")))
    "retrieve previously added docs for 'git commit'"))

(deftest test-fuzzy
  (is
    (re-find #"^grep" (first (fuzzy-get-docs-for "greo")))
    "fuzzy match for greo should find grep"))

(deftest similar
  (let [similar (map #(str "foobar" %) (range 4))]
    (doall (map #(add-docs % ["foobar baz"]) similar))
    (is
      (re-find #"Did.you.mean" (str (first (fuzzy-get-docs-for "foobar"))))
      "When there are many similar matches, show them to the user")))
