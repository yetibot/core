(ns yetibot.core.test.commands.observe
  (:require
    [clojure.test :refer :all]
    [clojure.string :refer [split]]
    [yetibot.core.commands.observe :refer :all]))

(deftest observe-parse-test
  (is (parse-observe-opts "-u lol x"))
  (is (contains?  (parse-observe-opts "-u lol -ewat x") :errors )
      "Should have errors with invalid event type"))
