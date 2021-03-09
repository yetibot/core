(ns yetibot.core.util
  (:require
    [clojure.string :as s]
    [cemerick.url :refer [url]]))

(defn filter-nil-vals
  "Takes a map and returns all of its non-nil values"
  [m]
  (into {} (remove (comp nil? second) m)))

(defn map-to-strs
  "takes a hash-map and parses it to a map-like sequence"
  [m]
  (map (fn [[k v]] (str (name k) ": " v)) m))

(def env
  (let [e (into {} (System/getenv))]
    (zipmap (map keyword (keys e)) (vals e))))

(defn make-config
  ^{:deprecated "0.1.0"}
  [required-keys] (into {} (map (fn [k] [k (env k)]) required-keys)))

(defn conf-valid?
  ^{:deprecated "0.1.0"}
  [c] (every? identity (vals c)))

(defmacro ensure-config
  ^{:deprecated "0.1.0"}
  [& body]
  `(when (every? identity ~'config)
     ~@body))

(defn psuedo-format
  "DEPRECATED: use yetibot.core.util.format/pseudo-format instead

   Similar to clojure.core/format, except it only supports %s, and it will
   replace all occurances of %s with the single arg. If there is no %s found, it
   appends the arg to the end of the string instead."
  ^{:deprecated "0.1.0"}
  [s arg]
  (if (re-find #"\%s" s)
    (s/replace s "%s" arg)
    (str s " " arg)))

(defn indices
  "Indices of elements of a collection matching pred"
  [pred coll]
  (keep-indexed #(when (pred %2) %1) coll))

;;; collection parsing

; helpers for all collection cmds
(defn ensure-items-collection
  "Ensures items is a collection. If not but is a string, will split on newlines,
   else will return nil"
  [items]
  (cond
    (map? items) (map-to-strs items)
    (coll? items) items
    (instance? String items) (s/split items #"\n")
    :else nil))

(comment
  (ensure-items-collection '(1 2 3))
  (ensure-items-collection [1 2 3])
  (ensure-items-collection {"one" 1 "two" 2})
  (ensure-items-collection "one: 1\ntwo: 2")
  (ensure-items-collection 123)
  )

(defn ensure-items-seqential
  "Ensures items is Sequential. If it's not, such as a map, it will transform it
   to a sequence of k: v strings."
  [items]
  (cond
    (sequential? items) items
    (map? items) (map-to-strs items)
    :else (seq items)))

(comment
  (ensure-items-seqential `(1 2 3))
  (ensure-items-seqential #{1 2 3})
  (ensure-items-seqential {"one" 1 "two" 2})
  )

; keys / vals helpers
(defn map-like?
  "determines if collection is a hash-map or map-like;
   map-like is when every collection item has a ':' delimiter"
  [items]
  (or (map? items)
      (every? #(re-find #".+:.+" %) items)))

(comment
  (map-like? {:easy 1})
  (map-like? ["key1:value1" "key2:value2"])
  (map-like? '("key1:value1" "key2:value2"))
  (map-like? ["is" "not" "map" "like"])
  )

(defn split-kvs
  "if collection is map-like?, split into a nested list [[k v]] instead
   of a map so as to maintain the order, else return nil"
  [items]
  (cond
    (map? items) (map vector (keys items) (vals items))
    (map-like? items) (map #(s/split % #":") items)
    :else nil))

(comment
  (split-kvs {:easy 1 :to "see"})
  (split-kvs ["key1:value1" "key2:value2"])
  (split-kvs ["is" "not" "map" "like"])
  )

(defn split-kvs-with
  "if collection is map-like?, accepts a function to map over the
   split keys from `split-kvs`, else returns original collection"
  [f items]
  (if-let [kvs (split-kvs items)]
    (map (comp s/trim f) kvs)
    items))

(comment
  (split-kvs-with first {"first" "is first" "second" "is second"})
  (split-kvs-with first ["is" "not" "map" "like"])
  )

;; image detection
(def image-pattern #"\.(png|jpe?g|gif|webp|svg)$")

(defn image?
  "Simple utility to detect if a URL represents an image purely by looking at
   the file extension (i.e. not too smart)."
  [possible-url]
  (try
    (let [{:keys [query path]} (url possible-url)]
      (or
        (re-find image-pattern path)
        ;; we indicate images from Wolfram are jpgs by tossing a &t=.jpg on it
        (= ".jpg" (get query "t"))))
    (catch Exception _
      false)))

(comment
  (image? "https://i.imgflip.com/2v045r.jpg")
  (image? "https://i.imgflip.com/2v045r.jpg?foo=bar")
  (image? "http://www5b.wolframalpha.com/Calculate/MSP/MSP6921ei892gfhh9i9649000058fg83ii266d342i?MSPStoreType=image/gif&s=46&t=.jpg")
  )