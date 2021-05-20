(ns yetibot.core.test.commands.observe
  (:require [midje.sweet :refer [=> facts fact contains]]
            [yetibot.core.commands.observe :as obs]))

(facts
 "about commands.observe"
 (fact
  "it returns observed options that have no errors, contains the specified arg,
   and recognizes the -u option with provided value"
  (let [{:keys [arguments errors options]} (obs/parse-observe-opts
                                            "-u lol x")]
    errors => empty?
    arguments => (contains "x")
    (:user-pattern options) => "lol"))
 (fact
  "it will return an error when providing invalid options, but still will parse
   whatever valid options it can find"
  (let [{:keys [arguments errors options]} (obs/parse-observe-opts
                                            "-u lol -ewat x")]
    errors => coll?
    (first errors) => (contains "Failed to validate")
    arguments => (contains "x")
    (:user-pattern options) => "lol")))
