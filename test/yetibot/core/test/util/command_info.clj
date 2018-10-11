(ns yetibot.core.test.util.command-info
  (:require
    [yetibot.core.parser :refer [parser]]
    yetibot.core.commands.echo
    [clojure.test :refer :all]
    [yetibot.core.util.command-info :refer :all]))

(deftest test-simple-command?
  (is (simple-command? (parser "echo"))
      "Should return true for a very simple command")

  (is (not (simple-command? (parser "echo | echo")))
      "Should be false on piped commands")

  (is (not (simple-command? (parser "echo `bar`")))
      "Should be false on commands with sub-expressions")
  )

(deftest test-command-execution-info
  (command-execution-info
    "foo bar"
    ))

