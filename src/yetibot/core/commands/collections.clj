(ns yetibot.core.commands.collections
  (:require
    [clojure.pprint :refer [pprint *print-right-margin*]]
    [cheshire.core :as json]
    [yetibot.core.interpreter :refer [handle-cmd]]
    [taoensso.timbre :as timbre :refer [info warn error debug]]
    [clojure.string :as s]
    [json-path :as jp]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.util.format :refer [format-exception-log]]
    [yetibot.core.util.command-info :refer [command-execution-info]]
    [yetibot.core.models.users :refer [min-user-keys]]
    [yetibot.core.util.command :refer [error?]]
    [yetibot.core.util :refer
     [psuedo-format split-kvs-with ensure-items-seqential
      ensure-items-collection]]))

(defn ensure-coll
  "Return nil if opts was set or return an error map otherwise"
  [{:keys [opts args]}]
  (or
    (ensure-items-collection opts)
    {:result/error
     (str "Expected a collection but you gave me `" args "`")}))

(defn coll-cmd
  "Helper to define commands that operate only on collections"
  [f]
  (with-meta
    (fn [cmd-args]
      (let [coll-or-error (ensure-coll cmd-args)]
        (if (error? coll-or-error)
          coll-or-error
          (let [result (f coll-or-error)]
            {:result/value result
             :result/data result}))))
    {:yb/cat #{:util :collection}}))

; random
(defn random
  "random <list> # returns a random item from <list>
   random # generate a random number"
  {:yb/cat #{:util :collection}}
  [{items :opts}]
  (if (not (empty? items))
    (rand-nth (ensure-items-collection items))
    (str (rand 100000))))

(cmd-hook #"random"
          _ random)

; shuffle
(def shuffle-cmd
  "shuffle <list>"
  (coll-cmd shuffle))

(cmd-hook #"shuffle"
          _ shuffle-cmd)

(def head-tail-regex #"(\d+).+")

; head / tail helpers
(defn head-or-tail
  [single-fn multi-fn n cmd-map]
  (let [coll-or-error (ensure-coll cmd-map)
        f (if (= 1 n) single-fn (partial multi-fn n))]
    (if (error? coll-or-error)
      coll-or-error
      (f coll-or-error))))

(def head (partial head-or-tail first take))

(def tail (partial head-or-tail last take-last))

; head
(defn head-1
  "head <list> # returns the first item from the <list>"
  {:yb/cat #{:util :collection}}
  [cmd-args]
  (head 1 cmd-args))

(defn head-n
  "head <n> <list> # return the first <n> items from the <list>"
  {:yb/cat #{:util :collection}}
  [{[_ n] :match :as cmd-args}]
  (head (read-string n) cmd-args))

(cmd-hook #"head"
          #"(\d+)" head-n
          _ head-1)

(cmd-hook #"take"
          #"(\d+)" head-n)

; tail
(defn tail-1
  "tail <list> # returns the last item from the <list>"
  {:yb/cat #{:util :collection}}
  [cmd-args] (tail 1 cmd-args))

(defn tail-n
  "tail <n> <list> # returns the last <n> items from the <list>"
  {:yb/cat #{:util :collection}}
  [{[_ n] :match :as cmd-args}]
  (tail (read-string n) cmd-args))

(cmd-hook #"tail"
          #"(\d+)" tail-n
          _ tail-1)

; droplast
(def drop-last-cmd
  "droplast <list> # drop the last item from <list>"
  (coll-cmd drop-last))

(cmd-hook ["droplast" #"^droplast$"]
  _ drop-last-cmd)

; rest
(def rest-cmd
  "rest <list> # returns the last item from the <list>"
  (coll-cmd rest))

(cmd-hook #"rest"
  _ rest-cmd)

; xargs
; example usage: !users | xargs attack
(defn xargs
  "xargs <cmd> <list> # run <cmd> for every item in <list> in parallel; behavior is similar to xargs(1)'s xargs -n1"
  {:yb/cat #{:util :collection}}
  [{:keys [args opts user] :as cmd-params}]
  (if (s/blank? args)
    opts ; passthrough if no args
    (if-let [itms (ensure-items-collection opts)]
      (let [cat (-> (str args " " (first opts))
                    command-execution-info :matched-sub-cmd meta :yb/cat)
            cmd-runner (if (contains? cat :async) 'map 'pmap)]
        (debug "xargs using cmd-runner:" cmd-runner "for command" (pr-str args))
        ((resolve cmd-runner)
         (fn [item]
           (try
             (let [{:result/keys [value error] :as cmd-result}
                   (apply handle-cmd
                          ;; item could be a collection, such as when xargs is
                          ;; used on nested collections, e.g.:
                          ;; repeat 5 jargon | xargs words | xargs head
                          (if (coll? item)
                            [args (merge cmd-params {:raw item :opts item})]
                            [(psuedo-format args item)
                             (merge cmd-params {:raw item :opts nil})]))
                   _ (info "xargs cmd-result" (pr-str cmd-result))]
               (or error value cmd-result))
             (catch Exception ex
               (error "Exception in xargs cmd-runner:" cmd-runner
                      (format-exception-log ex))
               ex)))
         itms))
      {:result/error (str "Expected a collection")})))

(cmd-hook #"xargs"
          _ xargs)

; join

(defn join
  "join <list> <separator> # joins list with optional <separator> or no separator if not specified. See also `unwords`."
  {:yb/cat #{:util :collection}}
  [{match :match items :opts :as cmd-args}]
  (let [coll-or-error (ensure-coll cmd-args)
        join-char (if (empty? match) "" match)]
    (if (error? coll-or-error)
      coll-or-error
      (s/join join-char coll-or-error))))

(cmd-hook #"join"
          #"(?is).+" join
          _ join)

; split
(defn split
  "split <pattern> <string> # split string with <pattern>"
  {:yb/cat #{:util :collection}}
  [{[_ split-by-str to-split] :match}]
  (let [split-by (re-pattern split-by-str)]
    (s/split to-split split-by)))

(cmd-hook #"split"
          #"(?is)^(\S+)\s+(.+)$" split)

; trim
(defn trim
  "trim <string> # remove whitespace from both ends of <string>"
  {:yb/cat #{:util :collection}}
  [{args :args}]
  (s/trim args))

(cmd-hook ["trim" #"^trim$"]
          _ trim)

; words
(defn words
  "words <string> # split <string> by spaces into a list"
  {:yb/cat #{:util :collection}}
  [{args :args}]
  (s/split args #" "))

(cmd-hook ["words" #"^words$"]
          _ words)

; unwords
(defn unwords
  "unwords <list> # join <list> with a single space"
  {:yb/cat #{:util :collection}}
  [{args :args opts :opts}]
  (if (nil? opts)
    args ; no collection, return the value as-is
    (s/join " " (ensure-items-collection opts))))

(cmd-hook ["unwords" #"^unwords$"]
          _ unwords)

; flatten
(defn flatten-cmd
  "flatten <nested list> # completely flattens a nested data struture after splitting on newlines"
  {:yb/cat #{:util :collection}}
  [{args :args opts :opts :as cmd-args}]
  (let [error-or-coll (ensure-coll cmd-args)]
    (if (error? error-or-coll)
      error-or-coll
      (->> error-or-coll
           flatten
           (map s/split-lines)
           flatten))))

(cmd-hook ["flatten" #"^flatten$"]
  _ flatten-cmd)

; letters
(defn letters
  "letters <string> # turn <string> into a sequence of individual letters"
  {:yb/cat #{:util :collection}}
  [{args :args}]
  (map str (seq args)))

(cmd-hook ["letters" #"^letters$"]
          _ letters)

; unletters
(def unletters
  "unletters <list> # join <list> without a delimiter"
  (coll-cmd (partial s/join "")))

(cmd-hook ["unletters" #"^unletters$"]
          _ unletters)

; set
(def set-cmd
  "set <list> # returns the set of distinct elements in <list> and does not preserve order"
  (coll-cmd set))

(cmd-hook #"set"
          _ set-cmd)

; list
(defn list-cmd
  "list <comma-or-space-delimited-items> # construct a list"
  {:yb/cat #{:util :collection}}
  [{:keys [args]}]
  (let [delimiter (if (re-find #"," args) #"," #"\s")]
    (map s/trim (s/split args delimiter))))

(cmd-hook #"list"
          _ list-cmd)


; count
(def count-cmd
  "count <list> # count the number of items in <list>"
  (coll-cmd (comp str count)))

(cmd-hook #"count"
          _ count-cmd)

; sum
(def sum-cmd
  "sum <list> # sum the items in <list>"
  (coll-cmd
    #(->> %
          (map (comp read-string str))
          (reduce +))))

(cmd-hook #"sum"
          _ sum-cmd)

; sort
(defn sort-cmd
  "sort <list> # alphabetically sort a list"
  {:yb/cat #{:util :collection}}
  [{items :opts}]
  (sort (ensure-items-collection items)))

(cmd-hook #"sort"
          _ sort-cmd)

; sortnum
(def sortnum-cmd
  "sortnum <list> # numerically sort a list"
  (coll-cmd
    (partial sort #(- (read-string %1) (read-string %2)))))

(cmd-hook #"sortnum"
          _ sortnum-cmd)

; grep
(defn slide-context [coll i n]
  (reduce
    (fn [acc i]
      (if-let [v (nth coll i nil)]
        (conj acc v)
        acc))
    []
    (range (- i n) (+ i n 1))))

(defn sliding-filter [slide-n filter-fn coll]
  (reduce
    (fn [acc i]
      (if (filter-fn (nth coll i))
        (conj acc (slide-context coll i slide-n))
        acc))
    []
    (range (count coll))))

(defn grep-data-structure
  "opts available:
     :context int - how many items around matched line to return"
  [pattern d & [opts]]
  (let [finder (partial re-find pattern)
        context-count (or (:context opts) 0)
        filter-fn (fn [i]
                    (cond
                      (string? i) (finder i)
                      (coll? i) (some finder (map str (flatten i)))))]
    (flatten (sliding-filter context-count filter-fn d))))

(defn grep-cmd
  "grep <pattern> <list> # filters the items in <list> by <pattern>
   grep -C <n> <pattern> <list> # filter items in <list> by <patttern> and include <n> items before and after each matched item"
  {:yb/cat #{:util :collection}}
  [{:keys [match opts args]}]
  (let [[n p] (if (sequential? match) (rest match) ["0" args])
        pattern (re-pattern (str "(?i)" p))
        items (-> opts ensure-items-collection ensure-items-seqential)]
    (if items
      (grep-data-structure pattern items {:context (read-string n)})
      {:result/error
       (str "Expected a collection but you only gave me `" args "`")})))

(cmd-hook #"grep"
          #"-C\s+(\d+)\s+(.+)" grep-cmd
          _ grep-cmd)

; tee
(defn tee-cmd
  "tee <list-or-args> # output <list-or-args> to chat then return it (useful for pipes)"
  {:yb/cat #{:util :collection}}
  [{:keys [opts args]}]
  (chat-data-structure (or opts args))
  (or opts args))

(cmd-hook #"tee"
          _ tee-cmd)

; reverse
(def reverse-cmd
  "reverse <list> # reverse the ordering of <list>"
  (coll-cmd reverse))

(cmd-hook #"reverse"
          _ reverse-cmd)

; range
(defn range-cmd
  "range <end> # create a list from 0 to <end> (exclusive)
   range <start> <end> # create a list from <start> (inclusive) to <end> (exclusive)
   range <start> <end> <step> # create a list from <start> (inclusive) to <end> (exclusive) by <step>

   Examples:
   range 2 => 0 1
   range 2 4 => 2 3
   range 0 6 2 => 0 2 4

   Results are returned as collections."
  {:yb/cat #{:util :collection}}
  [{:keys [match]}]
  (let [range-args (map read-string (rest match))]
    (->> range-args
         (apply range)
         (map str))))

(cmd-hook #"range"
  #"(\d+)\s+(\d+)\s+(\d+)" range-cmd
  #"(\d+)\s+(\d+)" range-cmd
  #"(\d+)" range-cmd)

; keys
(defn keys-cmd
  "keys <map> # return the keys from <map>"
  {:yb/cat #{:util :collection}}
  [{items :opts}]
  (timbre/debug (timbre/color-str :blue "keys")
                (timbre/color-str :green (pr-str items)))
  (if (map? items)
    (keys items)
    (split-kvs-with first items)))

(cmd-hook #"keys"
          _ keys-cmd)

; vals
(defn vals-cmd
  "vals <map> # return the vals from <map>"
  {:yb/cat #{:util :collection}}
  [{items :opts}]
  (if (map? items)
    (vals items)
    (split-kvs-with second items)))

(cmd-hook #"vals"
          _ vals-cmd)

;; raw
(defn raw-cmd
  "raw <coll> | <args> # output a string representation of piped <coll> or <args>"
  {:yb/cat #{:util :collection}}
  [{:keys [opts args]}]
  (pr-str (or opts args)))

(defn raw-all-cmd
  "raw all <coll> | <args> # output a string representation of all command context"
  {:yb/cat #{:util :collection}}
  [{:keys [user] :as command-args}]
  (let [minimal-user (select-keys user min-user-keys)
        cleaned-args (merge command-args {:user minimal-user})]
    (binding [*print-right-margin* 80]
      (with-out-str (pprint cleaned-args)))))

(cmd-hook #"raw"
  #"all" raw-all-cmd
  _ raw-cmd)

;; data

(defn extract-data-cmd
  "data <path> # extract data from the previous command with json-path syntax

   <path> syntax is like $.key or $.[0]
   For example, try:

   !about | data show
   !about | data $.about/url
   !about | data $.about/[*]

   Find many more path examples at:
   https://github.com/gga/json-path/blob/master/test/json_path/test/json_path_test.clj"
  {:yb/cat #{:util}}
  [{path :match
    :keys [args data]}]
  (info (timbre/color-str :blue "extra-data-cmd") path \newline
        (timbre/color-str :blue (pr-str data)))
  (if data
    (jp/at-path path data)
    "There is no `data` from the previous command 🤔"))

(defn show-data-cmd
  "data show # pretty print data from the previous command"
  {:yb/cat #{:util}}
  [{:keys [data] :as cmd-args}]
  (info "show-data-cmd" (pr-str (dissoc cmd-args :user)))
  (if data
    (binding [*print-right-margin* 80]
      (with-out-str (pprint data)))
    "There is no `data` from the previous command 🤔"))

(defn data-cmd
  "data # return the raw data output from the previous command"
  {:yb/cat #{:util}}
  [{:keys [data]}]
  (if data
    data
    "There is no `data` from the previous command 🤔"))

(cmd-hook #"data"
  #"show" show-data-cmd
  #".+" extract-data-cmd
  _ data-cmd)
