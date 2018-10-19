(ns yetibot.core.test.util.command
  (:require
    ;; yetibot.core.commands.echo
    [clojure.test :refer :all]
    [yetibot.core.util.command :refer :all]))

;; embedded commands

(deftest test-embedded-cmds
  (testing
    "Embedded commands that aren't actually known commands are not parsed"
    (is
      (empty?
        (embedded-cmds "`these` are the `invalid embedded commands`"))))

  (testing
    "Known embedded commands are properly extracted"
    (is
      (=
       ;; temp shouldn't be included because it's not a command/alias in the
       ;; test env
       (embedded-cmds "`echo your temp:` wonder what the `temp 98101` is")
       [[:expr
         [:cmd
          [:words "echo" [:space " "] "your" [:space " "] "temp:"]]]]))))
