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
      "Should be false on commands with sub-expressions"))

(deftest test-command-execution-info
  (testing "Fake command"
    (is
      (=
       {:parse-tree [:expr [:cmd [:words "foo" [:space " "] "bar"]]]
        :sub-commands nil
        :matched-sub-cmd nil
        :match nil
        :command "foo"
        :command-args "bar"}
       (command-execution-info "foo bar"))))
  (testing "Real command with opts"
    (let [{:keys [result match command-args]}
          (command-execution-info
            "head 2" {:run-command? true
                      :opts ["one" "two" "three"]})]
      (= match ["2" "2"])
      (= result ["one" "two"])
      (= command-args "2"))))
