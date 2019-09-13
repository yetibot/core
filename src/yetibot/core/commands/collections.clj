(ns yetibot.core.commands.collections
  (:require [clojure.pprint :refer [*print-right-margin* pprint]]
            [clojure.string :as s]
            [json-path :as jp]
            [taoensso.timbre :as timbre :refer [trace debug error info]]
            [yetibot.core.chat :refer [chat-data-structure]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.interpreter :refer [handle-cmd]]
            [yetibot.core.models.users :refer [min-user-keys]]
            [yetibot.core.util
             :refer
             [ensure-items-collection
              ensure-items-seqential
              psuedo-format
              split-kvs-with]]
            [yetibot.core.util.command :refer [error?]]
            [yetibot.core.util.command-info :refer [command-execution-info]]
            [yetibot.core.util.format :refer [format-exception-log]]))


;; Idea: maybe all commands should propagate all of their args. Unify things
;; like:
;; - the args passed to a cmd
;; - the return map of a cmd (optionally modifying the result, data,
;; data-collection)
;; - the args passed to observers
;; We'd need to identify the common denominators then spec out any differences
;; in shape for the various purposes and contexts.

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
    (fn [{:keys [data-collection] :as cmd-args}]
      (let [coll-or-error (ensure-coll cmd-args)]
        (if (error? coll-or-error)
          coll-or-error
          (let [result (f coll-or-error)]
            {:result/value result
             :result/data (f data-collection)}))))
    {:yb/cat #{:util :collection}}))

; random
(defn random
  "random <list> # returns a random item from <list>
   random # generate a random number"
  {:yb/cat #{:util :collection}}
  [{:keys [data-collection] items :opts}]
  (if (not (empty? items))
    (let [idx (rand-int (count (ensure-items-collection items)))
          item (nth items idx)
          data (when data-collection (nth data-collection idx))]
     {:result/value item
      :result/data data})
    (str (rand 100000))))

(cmd-hook #"random"
          _ random)

(def head-tail-regex #"(\d+).+")

; head / tail helpers
(defn head-or-tail
  [single-fn multi-fn n {:keys [data-collection]
                         :as cmd-map}]
  (let [coll-or-error (ensure-coll cmd-map)
        f (if (= 1 n) single-fn (partial multi-fn n))]
    (if (error? coll-or-error)
      coll-or-error
      (merge
        {:result/value (f coll-or-error)}
        (when data-collection
          {:result/data (f data-collection)})))))

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

;; tail
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

;; droplast
(defn drop-last-cmd
  "droplast <list> # drop the last item from <list>"
  {:yb/cat #{:util :collection}}
  [{:keys [data-collection] :as cmd-args}]
  (let [coll-or-error (ensure-coll cmd-args)]
    (if (error? coll-or-error)
      coll-or-error
      (let [value (drop-last coll-or-error)]
        {:result/value value
         :result/data (if data-collection
                        (drop-last data-collection)
                        value)}))))

(cmd-hook #"droplast"
  _ drop-last-cmd)

;; rest
(defn rest-cmd
  "rest <list> # returns the last item from the <list>"
  {:yb/cat #{:util :collection}}
  [{:keys [data-collection] :as cmd-args}]
  (let [coll-or-error (ensure-coll cmd-args)]
    (if (error? coll-or-error)
      coll-or-error
      (let [value (rest coll-or-error)]
        {:result/value value
         :result/data (if data-collection
                        (rest data-collection)
                        value)}))))

(cmd-hook #"rest"
  _ rest-cmd)

; xargs
; example usage: !users | xargs attack
(defn xargs
  "xargs <cmd> <list> # run <cmd> for every item in <list> in parallel; behavior is similar to xargs(1)'s xargs -n1"
  {:yb/cat #{:util :collection}}
  [{:keys [args opts user data data-collection] :as cmd-params}]
  (if (s/blank? args)
    ;; passthrough if no args
    {:result/value opts
     :result/data data
     :result/data-collection data-collection}
    (if-let [itms (or (ensure-items-collection opts)
                      (ensure-items-collection data-collection))]
      (let [cat (-> (str args " " (first opts))
                    command-execution-info :matched-sub-cmd meta :yb/cat)
            cmd-runner (if (contains? cat :async) 'map 'pmap)
            _ (debug "xargs using cmd-runner:" cmd-runner "for command"
                     (pr-str args))
            xargs-results
            ((resolve cmd-runner)
             (fn [item]
               (try
                 (let [cmd-result
                       (apply handle-cmd
                              ;; item could be a collection, such as when xargs
                              ;; is used on nested collections, e.g.:
                              ;; repeat 5 jargon | xargs words | xargs head
                              (if (coll? item)
                                [args (merge cmd-params {:raw item :opts item})]
                                [(psuedo-format args item)
                                 (merge cmd-params {:raw item :opts nil})]))

                       _ (debug "xargs cmd-result" (pr-str cmd-result))]
                   (if (map? cmd-result)
                     ;; if the result was already in map form return it as-is
                     cmd-result
                     ;; otherwise transform it to map form
                     {:result/value cmd-result
                      :result/data cmd-result}))
                 (catch Exception ex
                   (error "Exception in xargs cmd-runner:" cmd-runner
                          (format-exception-log ex))
                   ex)))
             itms)]
        {:result/value (map :result/value xargs-results)
         :result/data-collection (map :result/data-collection xargs-results)
         :result/data (map :result/data xargs-results)})
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
  [{:keys [args data data-collection]}]
  {:result/data data
   :result/data-collection data-collection
   :result/value (s/trim args)})

(cmd-hook #"trim"
  _ trim)

; words
(defn words
  "words <string> # split <string> by spaces into a list"
  {:yb/cat #{:util :collection}}
  [{args :args}]
  (s/split args #" "))

(cmd-hook #"words"
  _ words)

; unwords
(defn unwords
  "unwords <list> # join <list> with a single space"
  {:yb/cat #{:util :collection}}
  [{args :args opts :opts}]
  (if (nil? opts)
    args ; no collection, return the value as-is
    (s/join " " (ensure-items-collection opts))))

(cmd-hook #"unwords"
  _ unwords)

;; flatten
(defn flatten-cmd
  "flatten <nested list> # completely flattens a nested data struture after splitting on newlines"
  {:yb/cat #{:util :collection}}
  [{:keys [args opts data-collection] :as cmd-args}]
  (let [error-or-coll (ensure-coll cmd-args)]
    (if (error? error-or-coll)
      error-or-coll
      {:result/value (->> error-or-coll
                          flatten
                          (map s/split-lines)
                          flatten)
       :result/data (flatten data-collection)})))

(cmd-hook #"flatten"
  _ flatten-cmd)

; letters
(defn letters
  "letters <string> # turn <string> into a sequence of individual letters"
  {:yb/cat #{:util :collection}}
  [{args :args}]
  (map str (seq args)))

(cmd-hook #"letters"
  _ letters)

; unletters
(def unletters
  "unletters <list> # join <list> without a delimiter"
  (coll-cmd (partial s/join "")))

(cmd-hook #"unletters"
  _ unletters)

; set
(def set-cmd
  "set <list> # returns the set of distinct elements in <list> and does not preserve order"
  (coll-cmd (comp seq set)))

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
  (coll-cmd count))

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

(defn transform-opts-with-data
  [f {:keys [opts data-collection] :as cmd-args}]
  (let [coll-or-error (ensure-coll cmd-args)]
    (if (error? coll-or-error)
      coll-or-error
      (let [re-ordering (->> opts
                             (map-indexed vector)
                             f)
            ordering (map first re-ordering)
            opts (map second re-ordering)
            data (map (partial nth data-collection) ordering)]
        {:result/value opts
         :result/data data}))))

(defn shuffle-cmd
  "shuffle <list> # shuffle the order of a piped collection"
  {:yb/cat #{:util :collection}}
  [{:as cmd-args}]
  (transform-opts-with-data shuffle cmd-args))

(cmd-hook #"shuffle"
          _ shuffle-cmd)

; sort
(defn sort-cmd
  "sort <list> # alphabetically sort a list"
  {:yb/cat #{:util :collection}}
  [{items :opts :as cmd-args}]
  (transform-opts-with-data
    (partial sort-by second) cmd-args))

(cmd-hook #"sort"
  _ sort-cmd)

; sortnum
(defn sortnum-cmd
  "sortnum <list> # numerically sort a list"
  {:yb/cat #{:util :collection}}
  [{items :opts :as cmd-args}]
  (transform-opts-with-data
    (partial sort-by second #(- (read-string %1) (read-string %2))) cmd-args))

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
  [pattern d & [options]]
  (let [finder (partial re-find pattern)
        context-count (or (:context options) 0)
        filter-fn (fn [[idx item]]
                    (cond
                      (string? item) (finder item)
                      (coll? item) (some finder (map str (flatten item)))))]
    (apply concat (sliding-filter context-count filter-fn d))))

(defn grep-cmd
  "grep <pattern> <list> # filters the items in <list> by <pattern>
   grep -C <n> <pattern> <list> # filter items in <list> by <patttern> and include <n> items before and after each matched item"
  {:yb/cat #{:util :collection}}
  [{:keys [match opts args data-collection]}]
  (let [[n p] (if (sequential? match) (rest match) ["0" args])
        pattern (re-pattern (str "(?i)" p))
        items (-> opts ensure-items-collection ensure-items-seqential)]
    (if items
      (let [matches (grep-data-structure
                     pattern (map-indexed vector items)
                     {:context (read-string n)})
            ordering (map first matches)
            value (map second matches)
            data (when data-collection
                   (map (partial nth data-collection) ordering))
            ]
        {:result/value value
         :result/data data})
      {:result/error
       (str "Expected a collection but you only gave me `" args "`")})))

(cmd-hook #"grep"
  #"-C\s+(\d+)\s+(.+)" grep-cmd
  _ grep-cmd)

; tee
(defn tee-cmd
  "tee <list-or-args> # output <list-or-args> to chat then return it (useful for pipes)"
  {:yb/cat #{:util :collection}}
  [{:keys [data opts args]}]
  (chat-data-structure (or opts args))
  {:result/value (or opts args)
   :result/data data})

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
  [{items :opts :keys [data data-collection]}]
  {:result/value (if (map? items)
                   (keys items)
                   (split-kvs-with first items))
   :result/data data
   :result/data-collection data-collection})

(cmd-hook #"keys"
  _ keys-cmd)

; vals
(defn vals-cmd
  "vals <map> # return the vals from <map>"
  {:yb/cat #{:util :collection}}
  [{items :opts :keys [data data-collection]}]
  {:result/value
   (if (map? items)
     (vals items)
     (split-kvs-with second items))
   :result/data data
   :result/data-collection data-collection})

(cmd-hook #"vals"
  _ vals-cmd)

;; raw
(defn raw-cmd
  "raw <coll> | <args> # output a string representation of piped <coll> or <args>"
  {:yb/cat #{:util :collection}}
  [{:keys [data data-collection opts args]}]
  {:result/data data
   :result/data-collection data-collection
   :result/value (pr-str (or opts args))})

(defn raw-all-cmd
  "raw all <coll> | <args> # output a string representation of all command context"
  {:yb/cat #{:util :collection}}
  [{:keys [user data-collection] :as command-args}]
  (let [minimal-user (select-keys user min-user-keys)
        cleaned-args (merge command-args {:user minimal-user})]
    (binding [*print-right-margin* 80]
      {:result/value (with-out-str (pprint cleaned-args))
       :result/data-collection data-collection
       :result/data cleaned-args})))

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
  [{[_ path] :match
    :keys [args data]}]
  (debug (timbre/color-str :blue "extra-data-cmd")
         \newline
         (timbre/color-str :green {:path path})
         \newline
         (timbre/color-str :blue (type data) (pr-str data)))
  (if data
    (let [res (jp/at-path path data)]
      (timbre/info "extract-data-cmd result"
                   (timbre/color-str :blue (pr-str res)))
      {:result/data res
       :result/value (binding [*print-right-margin* 80]
                       (with-out-str (pprint res)))})
    {:result/error
     "There is no `data` from the previous command 🤔"}))

(defn show-data-cmd
  "data show # pretty print data from the previous command"
  {:yb/cat #{:util}}
  [{:keys [data] :as cmd-args}]
  (info "show-data-cmd" (pr-str (dissoc cmd-args :user)))
  (if data
    (binding [*print-right-margin* 80]
      {:result/value (with-out-str (pprint data))
       :result/data data})
    "There is no `data` from the previous command 🤔"))

(defn data-cmd
  "data # return the raw data output from the previous command"
  {:yb/cat #{:util}}
  [{:keys [data]}]
  (if data
    {:result/data data
     :result/value data}
    "There is no `data` from the previous command 🤔"))

(cmd-hook #"data"
  #"show" show-data-cmd
  ;; specifically ignore any args passed since they would be appended to the
  ;; command, but the `data` command ignores args and only looks at data
  #"(\$\S+).*" extract-data-cmd
  _ data-cmd)

(def max-repeat 10)

(defn repeat-cmd
  "repeat <n> <cmd> # repeat <cmd> <n> times"
  {:yb/cat #{:util}}
  [{[_ n cmd] :match :keys [user opts chat-source data data-collection]}]
  (let [n (read-string n)]
    (when (> n max-repeat)
      (chat-data-structure
        (format "LOL %s 🐴🐴 You can only repeat %s times 😇"
                (:name user) max-repeat)))
    (debug "repeat-cmd" {:chat-source chat-source
                         :user (keys user)
                         :data data
                         :data-collection data-collection
                         :opts opts
                         :n n :cmd cmd})
    (let [n (min max-repeat n)
          results
          (repeatedly
            n
            ;; We should use record-and-run-raw here, but that doesn't allow us
            ;; to pre-populate :opts, which is important for use cases like:
            ;;
            ;; !range 10 | repeat 5 random
            ;;
            ;; I wonder if a parse tree could express pre-populated args somehow
            ;; 🤔
            #(handle-cmd cmd {:chat-source chat-source
                              :data data
                              :data-collection data-collection
                              :user user
                              :opts opts}))

          ;; - some commands return {:result/value :result/data} structures
          ;; - others return an error like {:result/error}
          ;; - others just return a plain value
          ;; so look for all 3 forms
          values (map (fn [{:result/keys [value error] :as arg}]
                        (or value error arg)) results)
          data (map :result/data results)
          ]
      (info (pr-str (doall results)))
      {:result/value values
       :result/data data})))

(cmd-hook #"repeat"
  #"(\d+)\s(.+)" repeat-cmd)

(defn unquote-cmd
  "unquote <string> # remove double quotes from the beginning and end of <string>"
  {:yb/cat #{:util}}
  [{:keys [args data]}]
  {:result/value (-> "\"foo\" bar\""
                     (s/replace #"^\"" "")
                     (s/replace #"\"$" ""))
   :result/data data})

(cmd-hook #"unquote"
  _ unquote-cmd)
