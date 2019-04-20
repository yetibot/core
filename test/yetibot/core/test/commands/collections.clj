(ns yetibot.core.test.commands.collections
  (:require
    [yetibot.core.commands.collections :refer :all]
    [yetibot.core.util.command-info :refer [command-execution-info]]
    [clojure.test :refer :all]))

(deftest random-test
  (testing "Random with no args"
    (let [{:keys [matched-sub-cmd
                  result
                  result]} (command-execution-info
                             "random" {:run-command? true})
          random-number (read-string result)]
      (is (= matched-sub-cmd #'random)
          "It matches the expected `random` command handler")
      (is (number? random-number)
          "It should generate a random number when passed no args")))
  (testing "Random with args"
    (let [{:keys [matched-sub-cmd
                  result
                  result]} (command-execution-info "random" {:opts ["bar" "foo"]
                                                             :run-command? true})]
      (is (or (= "bar" result) (= "foo" result))
          "Random with a collection passed into it picks a random item from the
           collection"))))

(deftest grep-data-structure-test
  (is (= (grep-data-structure #"bar" [["foo" 1] ["bar" 2]])
         '("bar" 2))))

(deftest slide-context-test
  (is (= (slide-context (range 10) 3 2)
         [1 2 3 4 5]))
  (is (= (slide-context (range 10) 1 2)
         [0 1 2 3])
      "It should be shorter if there aren't enough results before")
  (is (= (slide-context (range 10) 9 3)
         [6 7 8 9])
      "It should be shorter if there aren't enough results at the end"))

(deftest sliding-filter-test
  (is (= (sliding-filter 1 #(> % 4) (range 10))
         [[4 5 6] [5 6 7] [6 7 8] [7 8 9] [8 9]]))
  (is (= (sliding-filter 1 odd? (range 6 10))
         [[6 7 8] [8 9]])))

(deftest grep-around-test
  (is (= (grep-data-structure
           #"yes"
           '("devth: foo" "devth: yes" "devth: bar" "devth: lol" "devth: ok" "devth: baz" "devth: !history | grep -C 2 yes")
           {:context 2})
         '("devth: foo" "devth: yes" "devth: bar" "devth: lol" "devth: ok" "devth: baz" "devth: !history | grep -C 2 yes")))
  (is (= (grep-data-structure
           #"foo"
           ["bar" "lol" "foo" "baz" "qux"]
           {:context 1})
         '("lol" "foo" "baz"))))

(deftest grep-cmd-test
  (is (= (grep-cmd {:args "foo"
                    :opts ["foo" "bar"]})
         '("foo")))
  (is (= (grep-cmd {:match (re-find #"-C\s+(\d+)\s+(.+)" "-C 1 baz")
                    :opts ["foo" "bar" "baz"]})
         '("bar" "baz"))))

(deftest flatten-test
  (testing "Simple case"
    (is (= (flatten-cmd {:opts ["1" "2" "3"]})
           ["1" "2" "3"])))
  (testing "Simple nested case"
    (is (= (flatten-cmd {:opts [["1" "2" "3"]]})
          ["1" "2" "3"])))
  (testing "Simple case with newlines"
    (is (= (flatten-cmd {:opts [(str 1 \newline 2 \newline 3 \newline)]})
          ["1" "2" "3"])))
  (testing "Nested case with newlines"
    (is (= (flatten-cmd {:opts [[[(str 1 \newline 2 \newline 3 \newline)]]]})
           ["1" "2" "3"]))))

(deftest words-test
  (= (:result
       (command-execution-info "words foo bar" {:run-command? true}))
     ["foo" "bar"]))

(deftest random-test
  (= (:result
       (command-execution-info "repeat 3 echo hi" {:run-command? true})
       {:parse-tree [:expr [:cmd [:words "repeat" [:space " "] "3" [:space " "]
                                  "echo" [:space " "] "hi"]]]
        :sub-commands [#"(\d+)\s(.+)" #'yetibot.core.commands.collections/repeat-cmd]
        :matched-sub-cmd #'yetibot.core.commands.collections/repeat-cmd
        :match ["3 echo hi" "3" "echo hi"]
        :command "repeat"
        :command-args "3 echo hi"
        :result ["hi" "hi" "hi"]}
       )))


(deftest data-test
  (testing "No data results in an error"
    (is
      (=
       #:result{:error "There is no `data` from the previous command 🤔"}
       (:result (command-execution-info "data $.[0]" {:run-command? true})))))

  (testing "Data should be preserved in data <path>"
    (is (=
         {:foo :bar}
         (-> (command-execution-info "data $.[0]" {:data [{:foo :bar}]
                                                   :run-command? true})
             :result :result/data)))))

(deftest data-propagation-test
  (testing "Collection utils should propagate data"
    (let [{{:result/keys [value data]} :result}
          (command-execution-info
            "random"
            {:opts (map str [1 2 3])
             :data-collection (map #(hash-map % %) [1 2 3])
             :data {:items (map #(hash-map % %) [1 2 3])
                    :count 3}
             :run-command? true})]
      (is
        (= (->> value read-string (repeat 2) (apply hash-map))
           data)
        "random should pull the corresponding random item out of the data and
         propagate it"))))
