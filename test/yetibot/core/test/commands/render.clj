(ns yetibot.core.test.commands.render
  (:require
    [yetibot.core.commands.render :refer :all]
    [yetibot.core.util.command-info :refer [command-execution-info]]
    [clojure.test :refer :all]))

(def execution-opts {:run-command? true
                     :data {:adj "lazy" :noun "water buffalo"}})

(deftest test-render-cmd
  (testing "A simple template"
    (is (= (-> (command-execution-info
                 "render the {{adj}} brown {{noun}}" execution-opts)
               :result :result/value)
           "the lazy brown water buffalo")))

  (testing "A template operating over a sequential produces a sequential"
    (is (= ["1" "2"]
           (-> (command-execution-info
              "render {{foo}}"
              (assoc execution-opts :data [{:foo 1} {:foo 2}]))
               :result :result/value))))

  (testing "An invalid template that throws an error"
    (is (= {:result/error "No filter defined with the name 'lol'"}
           (:result (command-execution-info
                      "render the {{adj|lol}} brown {{noun}}"
                      execution-opts)))))

  (testing "Some cool selmer filters"
    (is (= "the LAZY brown Water buffalo"
           (-> (command-execution-info
                 "render the {{adj|upper}} brown {{noun|capitalize}}"
                 execution-opts) :result :result/value)))))
