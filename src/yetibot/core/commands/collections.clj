(ns yetibot.core.commands.collections
  (:require
    [clojure.pprint :refer [pprint]]
    [cheshire.core :as json]
    [yetibot.core.interpreter :refer [handle-cmd]]
    [taoensso.timbre :as timbre :refer [info warn error]]
    [clojure.string :as s]
    [json-path :as jp]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.util.format :refer [format-exception-log]]
    [yetibot.core.util :refer
     [psuedo-format split-kvs-with ensure-items-seqential
      ensure-items-collection]]))

; random
(defn random
  "random <list> # returns a random item where <list> is a comma-separated list of items.
   Can also be used to extract a random item when a collection is piped to random."
  {:yb/cat #{:util}}
  [{items :opts}]
  (if (not (empty? items))
    (rand-nth (ensure-items-collection items))
    (str (rand 100000))))

(cmd-hook #"random"
          _ random)

; shuffle
(defn shuffle-cmd
  "shuffle <list>"
  {:yb/cat #{:util}}
  [{items :opts}]
  (shuffle (ensure-items-collection items)))

(cmd-hook #"shuffle"
          _ shuffle-cmd)

(def head-tail-regex #"(\d+).+")

; head / tail helpers
(defn head-or-tail
  [single-fn multi-fn n items]
  (let [f (if (= 1 n) single-fn (partial multi-fn n))]
    (f (ensure-items-collection items))))

(def head (partial head-or-tail first take))

(def tail (partial head-or-tail last take-last))

; head
(defn head-1
  "head <list> # returns the first item from the <list>"
  {:yb/cat #{:util}}
  [{items :opts}]
  (head 1 items))

(defn head-n
  "head <n> <list> # return the first <n> items from the <list>"
  {:yb/cat #{:util}}
  [{[_ n] :match items :opts}]
  (head (read-string n) items))

(cmd-hook #"head"
          #"(\d+)" head-n
          _ head-1)

(cmd-hook #"take"
          #"(\d+)" head-n)

; tail
(defn tail-1
  "tail <list> # returns the last item from the <list>"
  {:yb/cat #{:util}}
  [{items :opts}] (tail 1 items))

(defn tail-n
  "tail <n> <list> # returns the last <n> items from the <list>"
  {:yb/cat #{:util}}
  [{[_ n] :match items :opts}]
  (tail (read-string n) items))

(cmd-hook #"tail"
          #"(\d+)" tail-n
          _ tail-1)

; droplast
(defn drop-last-cmd
  "droplast <list> # drop the last item from <list>"
  {:yb/cat #{:util}}
  [{items :opts}]
  (drop-last (ensure-items-collection items)))

(cmd-hook ["droplast" #"^droplast$"]
          _ drop-last-cmd)

; rest
(defn rest-cmd
  "rest <list> # returns the last item from the <list>"
  {:yb/cat #{:util}}
  [{items :opts}] (rest items))

(cmd-hook #"rest"
          _ rest-cmd)

; xargs
; example usage: !users | xargs attack
(defn xargs
  "xargs <cmd> <list> # run <cmd> for every item in <list> in parallel; behavior is similar to xargs(1)'s xargs -n1"
  {:yb/cat #{:util}}
  [{:keys [args opts user] :as cmd-params}]
  (if (s/blank? args)
    opts ; passthrough if no args
    (let [itms (ensure-items-collection opts)]
      (pmap
        (fn [item]
          (try
            (apply handle-cmd
                   ; item could be a collection, such as when xargs is used
                   ; on nested collections, e.g.:
                   ; repeat 5 jargon | xargs words | xargs head
                   (if (coll? item)
                     [args (merge cmd-params {:raw item :opts item})]
                     [(psuedo-format args item) (merge cmd-params {:raw item :opts nil})]))
            (catch Exception ex
              (error "Exception in xargs pmap"
                     (format-exception-log ex))
              ex)))
        itms))))

(cmd-hook #"xargs"
          _ xargs)

; join
(defn join
  "join <list> <separator> # joins list with optional <separator> or no separator if not specified. See also `unwords`."
  {:yb/cat #{:util}}
  [{match :match items :opts}]
  (info (str "join with:'" match "'."))
  (let [join-char (if (empty? match) "" match)]
    (s/join join-char (ensure-items-collection items))))

(cmd-hook #"join"
          #"(?is).+" join
          _ join)

; split
(defn split
  "split <pattern> <string> # split string with <pattern>"
  {:yb/cat #{:util}}
  [{[_ split-by-str to-split] :match}]
  (let [split-by (re-pattern split-by-str)]
    (s/split to-split split-by)))

(cmd-hook #"split"
          #"(?is)^(\S+)\s+(.+)$" split)

; trim
(defn trim
  "trim <string> # remove whitespace from both ends of <string>"
  {:yb/cat #{:util}}
  [{args :args}]
  (s/trim args))

(cmd-hook ["trim" #"^trim$"]
          _ trim)

; words
(defn words
  "words <string> # split <string> by spaces into a list"
  {:yb/cat #{:util}}
  [{args :args}]
  (s/split args #" "))

(cmd-hook ["words" #"^words$"]
          _ words)

; unwords
(defn unwords
  "unwords <list> # join <list> with a single space"
  {:yb/cat #{:util}}
  [{args :args opts :opts}]
  (if (nil? opts)
    args ; no collection, return the value as-is
    (s/join " " (ensure-items-collection opts))))

(cmd-hook ["unwords" #"^unwords$"]
          _ unwords)

; flatten
(defn flatten-cmd
  "flatten <nested list> # completely flattens a nested data struture after splitting on newlines"
  {:yb/cat #{:util}}
  [{args :args opts :opts}]
  (->> (ensure-items-collection opts)
       flatten
       (map s/split-lines)
       flatten))

; letters
(defn letters
  "letters <string> # turn <string> into a sequence of individual letters"
  {:yb/cat #{:util}}
  [{args :args}]
  (map str (seq args)))

(cmd-hook ["letters" #"^letters$"]
          _ letters)

; unletters
(defn unletters
  "unletters <list> # join <list> without a delimiter"
  {:yb/cat #{:util}}
  [{opts :opts}]
  (s/join "" (ensure-items-collection opts)))

(cmd-hook ["unletters" #"^unletters$"]
          _ unletters)

; set
(defn set-cmd
  "set <list> # returns the set of distinct elements in <list>"
  {:yb/cat #{:util}}
  [{items :opts}]
  (set (ensure-items-collection items)))

(cmd-hook #"set"
          _ set-cmd)

; list
(defn list-cmd
  "list <comma-or-space-delimited-items> # construct a list"
  {:yb/cat #{:util}}
  [{:keys [args]}]
  (let [delimiter (if (re-find #"," args) #"," #"\s")]
    (map s/trim (s/split args delimiter))))

(cmd-hook #"list"
          _ list-cmd)


; count
(defn count-cmd
  "count <list> # count the number of items in <list>"
  {:yb/cat #{:util}}
  [{items :opts}]
  (str (count (ensure-items-collection items))))

(cmd-hook #"count"
          _ count-cmd)

; sum
(defn sum-cmd
  "sum <list> # sum the items in <list>"
  {:yb/cat #{:util}}
  [{items :opts}]
  (reduce + (map (comp read-string str) (ensure-items-collection items))))

(cmd-hook #"sum"
          _ sum-cmd)

; sort
(defn sort-cmd
  "sort <list> # sort a list"
  {:yb/cat #{:util}}
  [{items :opts}]
  (sort (ensure-items-collection items)))

(cmd-hook #"sort"
          _ sort-cmd)

; sortnum
(defn sortnum-cmd
  "sortnum <list> # numerically sort a list"
  {:yb/cat #{:util}}
  [{items :opts}]
  (sort #(- (read-string %1) (read-string %2)) (ensure-items-collection items)))

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
  {:yb/cat #{:util}}
  [{:keys [match opts args]}]
  (let [[n p] (if (sequential? match) (rest match) ["0" args])
        pattern (re-pattern (str "(?i)" p))
        items (-> opts ensure-items-collection ensure-items-seqential)]
    (grep-data-structure pattern items {:context (read-string n)})))

(cmd-hook #"grep"
          #"-C\s+(\d+)\s+(.+)" grep-cmd
          _ grep-cmd)

; tee
(defn tee-cmd
  "tee <list> # output <list> to chat and return list (useful for pipes)"
  {:yb/cat #{:util}}
  [{:keys [opts args]}]
  (chat-data-structure (or opts args))
  (or opts args))

(cmd-hook #"tee"
          _ tee-cmd)

; reverse
(defn reverse-cmd
  "reverse <list> # reverse the ordering of <list>"
  {:yb/cat #{:util}}
  [{items :opts}]
  (reverse (ensure-items-collection items)))

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
  {:yb/cat #{:util}}
  [{:keys [match]}]
  (let [range-args (map read-string (rest match))]
    (apply range range-args)))

(cmd-hook #"range"
  #"(\d+)\s+(\d+)\s+(\d+)" range-cmd
  #"(\d+)\s+(\d+)" range-cmd
  #"(\d+)" range-cmd)

; keys
(defn keys-cmd
  "keys <map> # return the keys from <map>"
  {:yb/cat #{:util}}
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
  {:yb/cat #{:util}}
  [{items :opts}]
  (if (map? items)
    (vals items)
    (split-kvs-with second items)))

(cmd-hook #"vals"
          _ vals-cmd)

;; raw
(defn raw-cmd
  "raw <coll> # output a string representation of the raw collection"
  {:yb/cat #{:util}}
  [{:keys [opts args]}]
  (pr-str (or opts args)))


(cmd-hook #"raw"
  _ raw-cmd)

;; data

(defn extract-data-cmd
  "data <path> # extract data from the previous command with json-path syntax"
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
  [{:keys [data]}]
  (if data
    (with-out-str (pprint data))
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
