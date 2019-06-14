(ns yetibot.core.test.db.util
  (:require
    [clojure.test :refer :all]
    [yetibot.core.db.util :as db.util]))

(deftest combine-wheres
  (testing "two non-empty where clauses"
    (=
     ["foo=? AND bar=?" ["bar" "baz"]]
     (db.util/combine-wheres
      (db.util/transform-where-map {:foo "bar"})
      (db.util/transform-where-map {:bar "baz"}))))

  (testing "first where is empty"
    (=
     ["bar=?" ["baz"]]
     (db.util/combine-wheres
      ["" []]
      (db.util/transform-where-map {:bar "baz"}))))

  (testing "second where is empty"
    (=
     ["bar=?" ["baz"]]
     (db.util/combine-wheres
      (db.util/transform-where-map {:bar "baz"})
      ["" []])))

  (testing "just where clause with no args"
    (=
     ["created_at >= CURRENT_DATE" []]
     (db.util/combine-wheres
      nil
      ["created_at >= CURRENT_DATE" []])))

  (testing "merging multiple query maps"
    (is (=
         (db.util/merge-queries
          {:select/clause "foo, bar"}
          {:where/map {:foo "bar"}}
          {:where/map {:qux "baz"}}
          {:where/clause "command LIKE ? AND created_at > ?"
           :where/args ["likethis" "yesterday"]}
          {:where/clause "is_yetibot IS ?"
           :where/args ["false"]
           :where/map {:updated_at "sometime"}}
          {:select/clause "baz"})
         {:where/map {:foo "bar", :qux "baz", :updated_at "sometime"},
          :where/clause "command LIKE ? AND created_at > ? AND is_yetibot IS ?",
          :where/args ["likethis" "yesterday" "false"],
          :select/clause "foo, bar, baz"}))))

(deftest where-eq-any-test
  (is
   (= (db.util/where-eq-any "foo" [1 2 3])
      #:where{:clause "(foo = ? OR foo = ? OR foo = ?)", :args [1 2 3]})))
