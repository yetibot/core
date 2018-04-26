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
       ["" []]
       ))))
