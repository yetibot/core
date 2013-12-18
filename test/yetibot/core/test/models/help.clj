(ns yetibot.core.test.models.help
  (:require
    [yetibot.core.models.help :refer :all]
    [clojure.test :refer :all]))

(defn add-some-docs []
  (add-docs
    "git commit"
    '("-a # all"
      "--amend # amend previous commit"
      "-m <msg>"
      "--interactive")))

(deftest add-and-retrieve-docs
         (add-some-docs) ; setup
         (is
           (not (empty? (get-docs-for "git commit")))
           "retrieve previously added docs for 'git commit'"))
