(ns yetibot.core.test.webapp.routes.graphql
  (:require
    [yetibot.core.webapp.routes.graphql :refer [graphql]]
    [clojure.test :refer :all]))

(deftest graphql-test
  (testing "Simple graphql query"
    (= (-> (graphql
             "{eval(expr: \"echo foo | echo bar\")}")
           :data
           first)
       [:eval "bar foo"])))
