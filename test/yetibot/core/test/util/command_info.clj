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
    (let [result (command-execution-info
                   "head 2" {:run-command? true
                             :opts ["one" "two" "three"]})]
      (is (= result
             {:parse-tree [:expr [:cmd [:words "head" [:space " "] "2"]]]
              :sub-commands (#"(\d+)" #'yetibot.core.commands.collections/head-n 
                                      #".*" #'yetibot.core.commands.collections/head-1)
              :matched-sub-cmd #'yetibot.core.commands.collections/head-n
              :match ["2" "2"]
              :command "head"
              :command-args "2"
              :result ("one" "two")}
             )
          "Should parse a real command, correctly match it, and execute it"))))
