(ns yetibot.core.test.util.command-info
  (:require [midje.sweet :refer [fact => truthy falsey]]
            [yetibot.core.parser :refer [parser]]
            [yetibot.core.util.command-info :as ci]
            [clojure.test :refer [deftest testing is]]
            yetibot.core.commands.collections
            yetibot.core.commands.echo
            [taoensso.timbre :refer [info]]))

(fact
 "simple-command? should return true for a very simple command"
 (ci/simple-command? (parser "echo")) => truthy)

(fact
 "simple-command? should be false on piped commands"
 (ci/simple-command? (parser "echo | echo")) => falsey)

(fact
 "simple-command? should be true on commands with sub-expressions"
 (ci/simple-command? (parser "echo `bar`")) => truthy)

(fact
 "command-execution-info returns partial data on an unknown command"
 (ci/command-execution-info "foo bar")
 => {:parse-tree [:expr [:cmd [:words "foo" [:space " "] "bar"]]]
     :sub-commands nil
     :matched-sub-cmd nil
     :match nil
     :command "foo"
     :command-args "bar"})

(fact
 "command-execution-info runs top level command even when a sub-expr is included"
 (ci/command-execution-info
  "echo foo bar `echo qux`"
  {:run-command? true}) => truthy)

(fact
 "command-execution-info returns correct data for a known command with opts"
 (let [{:keys [result match command-args]}
       (ci/command-execution-info
        "head 2" {:run-command? true
                  :opts ["one" "two" "three"]})]
   match => ["2" "2"]
   result => {:result/value ["one" "two"]}
   command-args => "2"))

(deftest random-test
  (testing "Random with no args"
    (let [{:keys [matched-sub-cmd result]} (ci/command-execution-info
                                            "random" {:run-command? true})
          random-number (read-string result)]
      (is (= matched-sub-cmd #'yetibot.core.commands.collections/random)
          "It matches the expected `random` command handler")
      (is (number? random-number)
          "It should generate a random number when passed no args")))
  (testing "Random with args"
    (let [{{result :result/value} :result} (ci/command-execution-info
                                            "random" {:opts ["bar" "foo"]
                                                      :run-command? true})]
      (is (or (= "bar" result) (= "foo" result))
          "Random with a collection passed into it picks a random item from the
           collection"))))

(deftest words-test
  (= (:result
      (ci/command-execution-info "words foo bar" {:run-command? true}))
     ["foo" "bar"]))

(deftest random-test2
  (= (:result
      (ci/command-execution-info "repeat 3 echo hi" {:run-command? true})
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
      (:result (ci/command-execution-info "data $.[0]" {:run-command? true})))))

  (testing "Data should be preserved in data <path>"
    (is (=
         {:foo :bar}
         (-> (ci/command-execution-info "data $.[0]" {:data [{:foo :bar}]
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
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "random" params)]
      (is
       (= (value->data value) data)
       "random should pull the corresponding random item out of the data and
         propagate it")))

  (testing "head should propagate data"
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "head 2" params)]
      (is (= [{"red" "red"} {"green" "green"}] data (map value->data value))))
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "head" params)]
      (info (pr-str value) (pr-str {:data data}))
      (is (= {"red" "red"} data (value->data value)))))

  (testing "repeat should accumulate the resulting data"
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "repeat 3 random" params)]
      (is (= data (map value->data value)))))

  (testing "keys and vals should propagate data"
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "keys" params)]
      (is (= ["red" "green" "blue"] value))
      (is (= sample-data data)))
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "vals"
                                                 (assoc params
                                                        :opts {:foo :bar}))]
      (is (= [:bar] value))
      (is (= sample-data data))))

  (testing "droplast and rest should propagate data"
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "droplast" params)]
      (is (= data (map value->data value))))

    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "rest" params)]
      (is (= data (map value->data value)))))

  (testing "sort propagates sorted data"
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "sort" params)]
      (info "sorted data" (pr-str data))
      (info "sorted opts" (pr-str value))
      (is (= ["blue" "green" "red"] value))
      (is (= data (map value->data value)))))

  (testing "sortnum propagates sorted data"
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "sortnum"
                                                 (assoc params
                                                        :opts ["2" "1" "3"]))]
      (is (= ["1" "2" "3"] value))
      (is (= [{"green" "green"} {"red" "red"} {"blue" "blue"}] data))))

  (testing "shuffle propagates shuffled data"
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "shuffle" params)]
      (is (= data (map value->data value)))))

  (testing "reverse propagates reversed data"
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                 "reverse" params)]
      (is (= data (map value->data value)))))

  (testing "grep propagates matched data"
    (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                                  ;; only matches "red" and
                                                  ;; "green"
                                                 "grep e.$" params)]
      (is (= data (map value->data value)))))

  (testing "xargs still works on simple commands that don't return a map"
    (is (= ["value is red" "value is green" "value is blue"]
           (-> (ci/command-execution-info
                 ;; only matches "red" and "green"
                "xargs echo value is" params)
               :result
               :result/value))))

  (testing "xargs accumulates and propagates data when it exists"
    (is (=
         (-> (ci/command-execution-info
                 ;; only matches "red" and
                 ;; "green"
              "xargs trim" params)
             :result)
         #:result{:value ["red" "green" "blue"]
                  :data-collection [nil nil nil]
                  :data [{"red" "red"} {"green" "green"} {"blue" "blue"}]})))

  (testing
   "xargs should properly propagate data for each item when data-collection is
    present"
    (is
     (= (-> (ci/command-execution-info
             "xargs render {{name}}"
             {:data [{:name "foo"} {:name "bar"} {:name "qux"}]
              :data-collection [{:name "foo"} {:name "bar"} {:name "qux"}]
              :opts ["foo" "bar" "qux"]
              :run-command? true})
            :result
            :result/value)
        ["foo" "bar" "qux"])))

  (testing "xargs falls back to data if opts not passed in"
    (is
     (=
      (-> (ci/command-execution-info
           "xargs keys"
           ;; remove opts, forcing it to fallback to data
           (-> params (dissoc :opts)))
          :result)
      #:result{:value [["red"] ["green"] ["blue"]]
               :data-collection [nil nil nil]
               :data [{"red" "red"} {"green" "green"} {"blue" "blue"}]}))))
