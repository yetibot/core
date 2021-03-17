(ns yetibot.core.test.db.util
  (:require [yetibot.core.db.util :as db.util]
            [midje.sweet :refer [=> fact facts]]))

(facts
 "about transform-where-map"
 (fact
  "handles an empty map and returns vector with empty values"
  (db.util/transform-where-map {}) =>
  ["" []])

 (fact
  "handles a single valued map and returns vector with
   expected values"
  (db.util/transform-where-map {:foo "bar"}) =>
  ["foo=?" ["bar"]])
 
 (fact
  "handles a multi valued map and returns vector with
   expected AND clause and values"
  (db.util/transform-where-map {:foo "bar"
                                :bar "baz"}) =>
  ["foo=? AND bar=?" ["bar" "baz"]]))

(facts
 "about combine-wheres"
 (fact
  "returns AND clause with two non-empty where clauses"
  (db.util/combine-wheres
   (db.util/transform-where-map {:foo "bar"})
   (db.util/transform-where-map {:bar "baz"})) =>
  ["foo=? AND bar=?" ["bar" "baz"]])
 
 (fact
  "returns clause when 1st where is empty"
  (db.util/combine-wheres
   ["" []]
   (db.util/transform-where-map {:bar "baz"})) =>
  ["bar=?" ["baz"]])

 (fact
  "returns clause when 2nd where is empty"
  (db.util/combine-wheres
   (db.util/transform-where-map {:bar "baz"})
   ["" []]) =>
  ["bar=?" ["baz"]])
 
 (fact
  "returns clause with with no args"
  (db.util/combine-wheres
   nil
   ["created_at >= CURRENT_DATE" []]) =>
  ["created_at >= CURRENT_DATE" []]))

(facts
 "about merge-queries"
 (fact
  "returns complex map when merging multiple query maps"
  (db.util/merge-queries
   {:select/clause "foo, bar"}
   {:where/map {:foo "bar"}}
   {:where/map {:qux "baz"}}
   {:where/clause "command LIKE ? AND created_at > ?"
    :where/args ["likethis" "yesterday"]}
   {:where/clause "is_yetibot IS ?"
    :where/args ["false"]
    :where/map {:updated_at "sometime"}}
   {:select/clause "baz"}) =>
  {:where/map {:foo "bar", :qux "baz", :updated_at "sometime"}
   :where/clause "command LIKE ? AND created_at > ? AND is_yetibot IS ?"
   :where/args ["likethis" "yesterday" "false"]
   :select/clause "foo, bar, baz"}))

(facts
 "about config"
 (fact
  "contains expected default keys and values"
  (let [cfg (db.util/config)]
    (:url cfg) => not-empty
    (get-in cfg [:table :prefix]) => db.util/default-table-prefix)))

(facts
 "about qualified-table-name"
 (fact
  "returns table with expected prefix")
 (db.util/qualified-table-name "hello") =>
 (str db.util/default-table-prefix "hello"))
