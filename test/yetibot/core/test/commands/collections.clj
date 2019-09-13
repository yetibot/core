(ns yetibot.core.test.commands.collections
  (:require
    [yetibot.core.commands.collections :refer :all]
    yetibot.core.commands.about
    [taoensso.timbre :refer [info]]
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
           (map-indexed vector ["devth: foo"
                                "devth: yes"
                                "devth: bar"
                                "devth: lol"
                                "devth: ok"
                                "devth: baz"
                                "devth: !history | grep -C 2 yes"])
           {:context 2})
         [[0 "devth: foo"]
          [1 "devth: yes"]
          [2 "devth: bar"]
          [3 "devth: lol"]
          [4 "devth: ok"]
          [5 "devth: baz"]
          [6 "devth: !history | grep -C 2 yes"]]))
  (is (= (grep-data-structure
           #"foo"
           (map-indexed vector ["bar" "lol" "foo" "baz" "qux"])
           {:context 1})
         '([1 "lol"] [2 "foo"] [3 "baz"]))))

(deftest grep-cmd-test
  (is (= (grep-cmd {:args "foo"
                    :opts ["foo" "bar"]})
         #:result{:value ["foo"], :data nil}))
  (is (= (grep-cmd {:match (re-find #"-C\s+(\d+)\s+(.+)" "-C 1 baz")
                    :opts ["foo" "bar" "baz"]})
         #:result{:value ["bar" "baz"], :data nil})))

(deftest flatten-test
  (testing "Simple case"
    (is (= (:result/value (flatten-cmd {:opts ["1" "2" "3"]}))
           ["1" "2" "3"])))
  (testing "Simple nested case"
    (is (= (:result/value (flatten-cmd {:opts [["1" "2" "3"]]}))
          ["1" "2" "3"])))
  (testing "Simple case with newlines"
    (is (= (:result/value
             (flatten-cmd {:opts [(str 1 \newline 2 \newline 3 \newline)]}))
          ["1" "2" "3"])))
  (testing "Nested case with newlines"
    (is (= (:result/value
             (flatten-cmd {:opts [[[(str 1 \newline 2 \newline 3 \newline)]]]}))
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
        :result ["hi" "hi" "hi"]})))

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

(def opts (map str ["red" "green" "blue"]))
;; construct some fake data that in theory represents the simplified
;; human-friendly opts above:
(def sample-data {:items (map #(hash-map % %) ["red" "green" "blue"]) :count 3})
(def sample-data-collection (:items sample-data))

(def params {:opts opts
             :data-collection sample-data-collection
             :data sample-data
             :run-command? true})

(defn value->data
  [value]
  (->> value (repeat 2) (apply hash-map)))

(deftest data-propagation-test

  (testing "random should propagate data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "random" params)]
      (is
       (= (value->data value) data)
       "random should pull the corresponding random item out of the data and
         propagate it")))

  (testing "head should propagate data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "head 2" params)]
      (is (= [{"red" "red"} {"green" "green"}] data (map value->data value))))
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "head" params)]
      (info (pr-str value) (pr-str {:data data}))
      (is (= {"red" "red"} data (value->data value)))))

  (testing "repeat should accumulate the resulting data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "repeat 3 random" params)]
      (is (= data (map value->data value)))))

  (testing "keys and vals should propagate data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "keys" params)]
      (is (= ["red" "green" "blue"] value))
      (is (= sample-data data)))
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "vals"
                                                 (assoc params
                                                        :opts {:foo :bar}))]
      (is (= [:bar] value))
      (is (= sample-data data))))

  (testing "droplast and rest should propagate data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "droplast" params)]
      (is (= data (map value->data value))))

    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "rest" params)]
      (is (= data (map value->data value)))))

  (testing "sort propagates sorted data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "sort" params)]
      (info "sorted data" (pr-str data))
      (info "sorted opts" (pr-str value))
      (is (= ["blue" "green" "red"] value))
      (is (= data (map value->data value)))))

  (testing "sortnum propagates sorted data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "sortnum"
                                                 (assoc params
                                                        :opts ["2" "1" "3"]))]
      (is (= ["1" "2" "3"] value))
      (is (= [{"green" "green"} {"red" "red"} {"blue" "blue"}] data))))

  (testing "shuffle propagates shuffled data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "shuffle" params)]
      (is (= data (map value->data value)))))

  (testing "reverse propagates reversed data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                 "reverse" params)]
      (is (= data (map value->data value)))))

  (testing "grep propagates matched data"
    (let [{{:result/keys [value data]} :result} (command-execution-info
                                                  ;; only matches "red" and
                                                  ;; "green"
                                                 "grep e.$" params)]
      (is (= data (map value->data value)))))

  (testing "xargs still works on simple commands that don't return a map"
    (is (= ["value is red" "value is green" "value is blue"]
           (-> (command-execution-info
                            ;; only matches "red" and
                            ;; "green"
                "xargs echo value is" params)
               :result
               :result/value))))

  (testing "xargs accumulates and propagates data when it exists"
    (is (= #:result{:value ["red" "green" "blue"],
                    :data-collection
                    [[{"red" "red"} {"green" "green"} {"blue" "blue"}]
                     [{"red" "red"} {"green" "green"} {"blue" "blue"}]
                     [{"red" "red"} {"green" "green"} {"blue" "blue"}]],
                    :data
                    [{:items [{"red" "red"} {"green" "green"} {"blue" "blue"}],
                      :count 3}
                     {:items [{"red" "red"} {"green" "green"} {"blue" "blue"}],
                      :count 3}
                     {:items [{"red" "red"} {"green" "green"} {"blue" "blue"}],
                      :count 3}]}
           (-> (command-execution-info
                 ;; only matches "red" and
                 ;; "green"
                "xargs trim" params)
               :result))))

  (testing "xargs falls back to data if opts not passed in"
    (is
     (=
      (-> (command-execution-info
           "xargs keys"
           ;; remove opts, forcing it to fallback to data
           (-> params (dissoc :opts)))
          :result)
      #:result{:value [["red"] ["green"] ["blue"]],
               :data-collection
               [[{"red" "red"} {"green" "green"} {"blue" "blue"}]
                [{"red" "red"} {"green" "green"} {"blue" "blue"}]
                [{"red" "red"} {"green" "green"} {"blue" "blue"}]],
               :data
               [{:items [{"red" "red"} {"green" "green"} {"blue" "blue"}],
                 :count 3}
                {:items [{"red" "red"} {"green" "green"} {"blue" "blue"}],
                 :count 3}
                {:items [{"red" "red"} {"green" "green"} {"blue" "blue"}],
                 :count 3}]}))))
