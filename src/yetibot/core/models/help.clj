(ns yetibot.core.models.help
  "Store help docs in memory and provide convenience functions to access them.

   Note: help docs for built in commands and docs for aliases are stored
   separately in their own atoms (`docs` and `alias-docs`).

   Note: fuzzy matching only works on built in commands (not aliases)."
  (:require [clojure.string :as s]
            [taoensso.timbre :refer [info warn error]]
            [clj-fuzzy.metrics :refer [levenshtein]]
            [clojure.data.json :as json]))

(defonce ^{:doc "Map of prefix to corresponding command docs"}
  docs (atom {}))

(defonce ^{:doc "Map of prefix to corresponding alias docs"}
  alias-docs (atom {}))

(comment
  (reset! docs {})
  (reset! alias-docs {})
  )

(def distance-threshold "Minimum distance for fuzzy prefix matching" 3)

(def distance-range
  "The range of acceptible distances to look for, beginning with 0 up
   to `distance-threshold` exclusive"
  (range (inc distance-threshold)))

(defn add-docs
  "Add docstrings for a prefix to the appropriate atom, depending on whether or
   not it is an alias"
  ([prefix cmds] (add-docs prefix cmds false))
  ([prefix cmds alias?]
   ;; only add them if cmds contains docstrings (they might not if there was no
   ;; metadata on the sub-command functions, as is the case when creating
   ;; aliases)
   (when-let [cmds (->> cmds (remove nil?) seq)]
     ;; add to the docs atom using prefix string as the key
     (let [docs-atom (if alias? alias-docs docs)
           cmds (->> cmds
                     ;; trim up that whitespace 😑
                     (map (comp
                            (partial s/join \newline)
                            (partial map s/trim)
                            s/split-lines))
                     set)]
       (swap! docs-atom conj {(str prefix) cmds})))))

(defn get-docs [] @docs)
(defn get-alias-docs [] @alias-docs)

(defn remove-docs
  "Remove (alias-)docs from help store"
  [prefix]
  (swap! alias-docs dissoc prefix)
  (swap! docs dissoc prefix))

(defn get-docs-for
  "Return a set of all doc entries for a given prefix"
  [prefix]
  (or
   (get (get-docs) prefix)
   (get (get-alias-docs) prefix)))

(defn distance-to-prefix
  "Take a string and return prefixes grouped by distance"
  [s]
  (let [dist (partial levenshtein s)]
    (->> (get-docs) keys (group-by dist))))

(defn nearest-match-within-range
  [s]
  (let [prefix-distances (distance-to-prefix s)]
    (some
      #(when-let [topics (prefix-distances %)] [% topics])
      distance-range)))

(defn fuzzy-get-docs-for
  "Tries to find docs for a given topic based on Levenshtein distance. If the
   Levenshtein distance is within `distance-range`, it'll either:
   - return help for the nearest distance topic, only if no other topics share
     the same distance
   - return the list of topics with the same distance"
  [topic]
  (when-let [[dist matches] (nearest-match-within-range topic)]
    (if (> (count matches) 1)
      ; multiple matches, suggest them to the user
      [(str "No matches for \"" topic "\". Did you mean: "
            (s/join ", " matches) "?")]
      (get-docs-for (first matches)))))
