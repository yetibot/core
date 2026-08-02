(ns yetibot.core.test.commands.collections
  (:require
   [yetibot.core.commands.collections :as colls]
   [yetibot.core.util.command-info :as ci]
   [clojure.string :as string]
   [midje.sweet :refer [fact facts => contains every-checker]]
   yetibot.core.commands.echo
   yetibot.core.commands.render))

(facts "slide-context"
       (fact "gives the context" (colls/slide-context (range 10) 3 2) => [1 2 3 4 5])
       (fact "is shorter if there aren't enough results before"
             (colls/slide-context (range 10) 1 2) => [0 1 2 3])
       (fact "is shorter if there aren't enough results at the end"
             (colls/slide-context (range 10) 9 3) => [6 7 8 9]))

(facts "sliding-filter"
       (fact (colls/sliding-filter 1 #(> % 4) (range 10)) =>
             [[4 5 6] [5 6 7] [6 7 8] [7 8 9] [8 9]])
       (fact (colls/sliding-filter 1 odd? (range 6 10)) =>
             [[6 7 8] [8 9]]))

(facts "grep context"
       (fact "for multiple matches"
             (colls/grep-data-structure #"yes"
                                        (map-indexed vector
                                                     ["devth: foo"
                                                      "devth: yes"
                                                      "devth: bar"
                                                      "devth: lol"
                                                      "devth: ok"
                                                      "devth: baz"
                                                      "devth: !history | grep -C 2 yes"])
                                        {:context 2})
             =>
             [[0 "devth: foo"]
              [1 "devth: yes"]
              [2 "devth: bar"]
              [3 "devth: lol"]
              [4 "devth: ok"]
              [5 "devth: baz"]
              [6 "devth: !history | grep -C 2 yes"]])
       (fact "for single match"
             (colls/grep-data-structure #"foo"
                                        (map-indexed vector
                                                     ["bar" "lol" "foo" "baz" "qux"])
                                        {:context 1})
             =>
             '([1 "lol"] [2 "foo"] [3 "baz"]))
       (fact "no overlapping matches"
             (colls/grep-data-structure #"foo"
                                        (map-indexed vector
                                                     ["foo" "bar" "baz" "foo"])
                                        {:context 2})
             =>
             [[0 "foo"]
              [1 "bar"]
              [2 "baz"]
              [3 "foo"]]))

(facts
 "grep-cmd-test"
 (fact "gives full match"
       (colls/grep-cmd {:match "foo" :opts ["foo" "bar"]})
       =>
       #:result{:value ["foo"] :data nil})
 (fact "gives partial match"
       (colls/grep-cmd {:match "foo" :opts ["foobar" "baz"]})
       =>
       #:result{:value ["foobar"] :data nil})
 (fact "with -C flag gives context"
       (colls/grep-surrounding {:match (re-find #"-C\s+(\d+)\s+(.+)" "-C 1 baz")
                                :opts ["foo" "bar" "baz"]})
       =>
       #:result{:value ["bar" "baz"], :data nil})
 (fact "with -v flag gives inverted match"
       (colls/inverted-grep {:match (re-find #"-v\s+(.+)" "-v bar")
                             :opts ["foo" "bar" "baz"]})
       =>
       #:result{:value ["foo" "baz"], :data nil}))

(facts
 "about flatten-cmd"
 (let [cmn-res ["1" "2" "3"]]
   (fact
    "simple case using vector"
    (:result/value (colls/flatten-cmd {:opts ["1" "2" "3"]}))
    => cmn-res)
   (fact
    "simple case using nested vector"
    (:result/value (colls/flatten-cmd {:opts [["1" "2" "3"]]}))
    => cmn-res)
   (fact
    "case using single str w/ newlines in vector"
    (:result/value (colls/flatten-cmd
                    {:opts [(str 1 \newline 2 \newline 3 \newline)]}))
    => cmn-res)
   (fact
    "case using single str w/ newlines in nested vector"
    (:result/value (colls/flatten-cmd
                    {:opts [[[(str 1 \newline 2 \newline 3 \newline)]]]}))
    => cmn-res)))

(facts
 "about command-execution-info using 'data' collections command"

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
 "about command-execution-info using various collections commands"
 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "random" params)]
   (fact
    "using 'random' collections command, it should pull the corresponding
     random item out of the data and propagate it"
    data => (value->data value)))

 (let [{:keys [matched-sub-cmd result]} (ci/command-execution-info
                                         "random" {:run-command? true})
       random-number (read-string result)]
   (fact
    "using 'random' collections command with no args, it matches expected
     'random' command handler AND generates a randome number"
    matched-sub-cmd => #'yetibot.core.commands.collections/random
    random-number => number?))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "head 2" params)]
   (fact
    "using 'head 2' collections command, it should propagate multiple sets
     of data"
    data => [{"red" "red"} {"green" "green"}]
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "head" params)]
   (fact
    "using 'head' collections command, it should propagate sets of data"
    data => {"red" "red"}
    data => (value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "repeat 3 random" params)]
   (fact
    "using 'repeat 3 random' collections command, it should accumulate
     resulting data"
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "keys" params)]
   (fact
    "using 'keys' collections command, it should propagate 'key' data"
    value => ["red" "green" "blue"]
    data => sample-data))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "vals"
                                              (assoc params
                                                     :opts {:foo :bar}))]
   (fact
    "using 'vals' collections command, it should propagate 'value' data"
    value => [:bar]
    data => sample-data))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "droplast" params)]
   (fact
    "using 'droplast' collections command, it should drop last item and propagate
     remaining data"
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "rest" params)]
   (fact
    "using 'rest' collections command, it should drop 1st item and propagate
     remaining data"
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "sort" params)]
   (fact
    "using 'sort' collections command, it should sort items in collection
     and propagate"
    value => ["blue" "green" "red"]
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "sortnum"
                                              (assoc params
                                                     :opts ["2" "1" "3"]))]
   (fact
    "using 'sortnum' collections command, it should sort items in collection
     based on the item index position and propagate"
    value => ["1" "2" "3"]
    data => [{"green" "green"} {"red" "red"} {"blue" "blue"}]))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "shuffle" params)]
   (fact
    "using 'shuffle' collecitons command, it should shuffle data
     and propagate"
    data => (map value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "reverse" params)]
   (fact
    "using 'reverse' collections command, it should propagate reversed data"
    data => (map value->data value)))

 (let [cmd-coll '("words" "foo" "bar")
       {result :result} (ci/command-execution-info (string/join " " cmd-coll)
                                                   {:run-command? true})]
   (fact
    "using 'words' collections command, it returns coll of the word args,
     spliting on whitespace"
    result => (pop cmd-coll)))

 (let [{result :result} (ci/command-execution-info "unwords " params)]
   (fact
    "using 'unwords' collections command, it should take the list of items
     in the color collection and return it as a string with a space delim"
    result => "red green blue"))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "grep e.$" params)]
   (fact
    "using 'grep e.$' collections command, it should only match green and
     red and propagate the matched data"
    data => (map value->data value)))

 (let [values (-> (ci/command-execution-info "xargs echo value is" params)
                  :result
                  :result/value)]
   (fact
    "using 'xargs echo value is' collections command, it should work on
     simple commands that don't return a map"
    values => ["value is red" "value is green" "value is blue"]))

 (let [values (-> (ci/command-execution-info "xargs trim" params)
                  :result)]
   (fact
    "using 'xargs trim' collections command, it should accumulate and
     propagate data when it exists"
    values => (contains {:result/value ["red" "green" "blue"]})
    values => (contains {:result/data [{"red" "red"} {"green" "green"}
                                       {"blue" "blue"}]})
    values => (contains {:result/data-collection [nil nil nil]})))

 (let [values (-> (ci/command-execution-info "xargs keys"
                                             (-> params (dissoc :opts)))
                  :result)]
   (fact
    "using 'xargs keys' collections command, it should fall back to data
     if opts not passed in"
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
    "using 'xargs render {{name}}' collections command, it should properly
     propagate data for each item when data-collection is present"
    values => ["foo" "bar" "qux"]))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "tail" params)]
   (fact
    "using 'tail' no args collections command, it should return the last item
     in the collection and propagate as data"
    value => "blue"
    data => (value->data value)))

 (let [{{:result/keys [value data]} :result} (ci/command-execution-info
                                              "tail 2" params)]
   (fact
    "using 'tail 2' collections command, it should propagate multiple sets
     of data, pulling the last 2 items from the collection"
    data => [{"green" "green"} {"blue" "blue"}]
    data => (map value->data value)))

 (let [{result :result} (ci/command-execution-info "letters abc"
                                                   {:run-command? true})]
   (fact
    "using 'letters' collections command, it should return a coll of whatever
     string was passed to the command"
    result => ["a" "b" "c"]))

 (let [{{:result/keys [value]} :result}
       (ci/command-execution-info "unletters" params)]
   (fact
    "using 'unletters' collections command, it should take whatever collection
     is passed and combine them into a nonspaced string"
    value => "redgreenblue"))

 (let [{result :result} (ci/command-execution-info "join" params)]
   (fact
    "using 'join' collections command, it should join the param items into a
     nonspaced string"
    result => "redgreenblue"))

 (let [{{:result/keys [value]} :result}
       (ci/command-execution-info "set"
                                  {:opts ["1" "1" "1" "2"]
                                   :run-command? true})]
   (fact
    "using 'set' collections command, it should take collection arg and return
     a list of unique items"
    value => ["1" "2"]))

 (let [{result :result}
       (ci/command-execution-info "list 1 2 3"
                                  {:run-command? true})]
   (fact
    "using 'list' collections command, it should take string args and return them
     as a list"
    result => ["1" "2" "3"]))

 (let [{{:result/keys [value]} :result}
       (ci/command-execution-info "count" params)]
   (fact
    "using 'count' collections command, it should return the number of items
     in the list arg"
    value => 3))

 (let [{{:result/keys [value]} :result}
       (ci/command-execution-info "sum"
                                  {:opts ["1" "1" "1"]
                                   :run-command? true})]
   (fact
    "using 'sum' collections command, it should add all the args in the provided
     list"
    value => 3))

 (let [{result :result}
       (ci/command-execution-info "range 3"
                                  {:run-command? true})]
   (fact
    "using 'range' collections command, it should return a zero based indexed
     list of elements = to arg provided"
    result => ["0" "1" "2"]))

 (let [{{:result/keys [value]} :result}
       (ci/command-execution-info "unquote dontmatterwhatiputhere"
                                  {:run-command? true})]
   (fact
    "using 'unquote' collections command, it should always return 'foo\" bar';
     pretty sure this is a bug :)"
    value => "foo\" bar")))
