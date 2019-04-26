(ns yetibot.core.test.commands.decode
  (:require
    [yetibot.core.commands.decode :refer :all]
    [yetibot.core.util.command-info :refer [command-execution-info]]
    [clojure.test :refer :all]))

(def execution-opts {:run-command? true})

(deftest decode-test
  (testing "HTML encoded strings get decoded"
    (is (= (-> (command-execution-info
                 "decode Come check out Trevor Hartman&#39;s talk &quot;Growing a Chatops Platform and Having Fun with Clojure&quot; where we take a look at the development of Yetibot!"
                 execution-opts)
               :result
               :result/value)
           "Come check out Trevor Hartman's talk \"Growing a Chatops Platform and Having Fun with Clojure\" where we take a look at the development of Yetibot!"
           ))))
