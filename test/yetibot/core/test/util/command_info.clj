(ns yetibot.core.test.util.command-info
  (:require [midje.sweet :refer [fact facts => truthy falsey every-checker
                                 contains]]
            [clojure.string :as string]
            [yetibot.core.parser :refer [parser]]
            [yetibot.core.util.command-info :as ci]
            yetibot.core.commands.collections
            yetibot.core.commands.echo
            yetibot.core.commands.render))

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

(facts
 "about command-execution-info using 'random' command"
 (let [{:keys [matched-sub-cmd result]} (ci/command-execution-info
                                         "random" {:run-command? true})
       random-number (read-string result)]
   (fact
    "with no args, it matches expected 'random' command handler"
    matched-sub-cmd => #'yetibot.core.commands.collections/random)
   (fact
    "with no args, it generates a random number"
    random-number => number?))
 (let [{{result :result/value} :result} (ci/command-execution-info
                                         "random" {:opts ["bar" "foo"]
                                                   :run-command? true})]
   (fact
    "with args, picks random item from provided collection"
    result => #"bar|foo")))

(facts
 "about command-execution-info using 'words' command"
 (let [cmd-coll '("words" "foo" "bar")
       {result :result} (ci/command-execution-info (string/join " " cmd-coll)
                                                   {:run-command? true})]
   (fact
    "returns the 'words' cmd args as coll, split on whitespace"
    result => (pop cmd-coll))))

(facts
 "about command-execution-info using 'repeat' command"
 (let [{:keys [command command-args matched-sub-cmd]
        {value :result/value} :result} (ci/command-execution-info
                                        "repeat 3 echo hi"
                                        {:run-command? true})]
   (fact
    "returns expected vals for command related vals"
    command => "repeat"
    command-args => "3 echo hi"
    value => ["hi" "hi" "hi"]
    matched-sub-cmd => #'yetibot.core.commands.collections/repeat-cmd)))

(facts
 "about command-execution-info using 'data' command"
 (let [{{error :result/error} :result} (ci/command-execution-info
                                        "data $.[0]" {:run-command? true})]
   (fact
    "when no data using command, results in an error str"
    error => (every-checker
              string?
              (contains
               "There is no `data` from the previous command 🤔"))))
 (let [data {:foo :bar}
       {{result :result/data} :result} (ci/command-execution-info
                                        "data $.[0]" {:data [data]
                                                      :run-command? true})]
   (fact
    "Data should be preserved in data <path>"
    data => result)))

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

(facts
 "about command-execution-info"
 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "random" params)]
   (fact
    "using 'random' command, should pull the corresponding random item
     out of the data and propagate it"
    data => (value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "head 2" params)]
   (fact
    "using 'head 2' command, should propagate multiple sets of data"
    data => [{"red" "red"} {"green" "green"}]
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "head" params)]
   (fact
    "using 'head' command, should propagate sets of data"
    data => {"red" "red"}
    data => (value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "repeat 3 random" params)]
   (fact
    "using 'repeat 3 random' command, should accumulate resulting data"
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "keys" params)]
   (fact
    "using 'keys' command, should propagate 'key' data"
    value => ["red" "green" "blue"]
    data => sample-data))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "vals"
                                              (assoc params
                                                     :opts {:foo :bar}))]
   (fact
    "using 'vals' command, should propagate 'value' data"
    value => [:bar]
    data => sample-data))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "droplast" params)]
   (fact
    "using 'droplast' command, should drop last item and propagate
     remaining data"
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "rest" params)]
   (fact
    "using 'rest' command, should drop 1st item and propagate
     remaining data"
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "sort" params)]
   (fact
    "using 'sort' command, should sort items in collection and propagate"
    value => ["blue" "green" "red"]
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "sortnum"
                                              (assoc params
                                                     :opts ["2" "1" "3"]))]
   (fact
    "using 'sortnum' command, should sort items in collection based on
     item index position and propagate"
    value => ["1" "2" "3"]
    data => [{"green" "green"} {"red" "red"} {"blue" "blue"}]))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "shuffle" params)]
   (fact
    "using 'shuffle' command, should shuffle data and propagate"
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "reverse" params)]
   (fact
    "using 'reverse' command, should propagate reversed data"
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "grep e.$" params)]
   (fact
    "using 'grep e.$' command, should only match green and red and
    propagate the matched data"
    data => (map value->data value)))

 (let [values (-> (ci/command-execution-info "xargs echo value is" params)
                  :result
                  :result/value)]
   (fact
    "using 'xargs echo value is' command, should work on simple commands
     that don't return a map"
    values => ["value is red" "value is green" "value is blue"]))

 (let [values (-> (ci/command-execution-info "xargs trim" params)
                  :result)]
   (fact
    "using 'xargs trim' command, it should accumulate and propagate
     date when it exists"
    values => (contains {:result/value ["red" "green" "blue"]})
    values => (contains {:result/data [{"red" "red"} {"green" "green"}
                                       {"blue" "blue"}]})
    values => (contains {:result/data-collection [nil nil nil]})))

 (let [values (-> (ci/command-execution-info "xargs keys"
                                             (-> params (dissoc :opts)))
                  :result)]
   (fact
    "using 'xargs keys' command, it should fall back to data if opts
     not passed in"
    values => (contains {:result/value [["red"] ["green"] ["blue"]]})
    values => (contains {:result/data [{"red" "red"} {"green" "green"}
                                       {"blue" "blue"}]})
    values => (contains {:result/data-collection [nil nil nil]})))

 (let [values (-> (ci/command-execution-info
                   "xargs render {{name}}"
                   {:data [{:name "foo"} {:name "bar"} {:name "qux"}]
                    :data-collection [{:name "foo"} {:name "bar"} {:name "qux"}]
                    :opts ["foo" "bar" "qux"]
                    :run-command? true})
                  :result
                  :result/value)]
   (fact
    "using 'xargs render {{name}}' command, it should properly propagate
     data for each item when data-collection is present"
    values => ["foo" "bar" "qux"])))
