(ns yetibot.core.test.webapp.routes.graphql
  (:require
    [yetibot.core.webapp.routes.graphql :refer [graphql]]
    [clojure.test :refer :all]))

(deftest graphql-test
  (testing "Simple graphql query"
    (is
      (= (-> (graphql
               "{eval(expr: \"echo foo | echo bar\")}"
               {})
             :data
             first)
         [:eval ["bar foo"]])))

  (testing "Query with Variables"
    (is
      (not
        (:errors
          (graphql
            "query stats($timezone_offset_hours: Int!) {
             stats(timezone_offset_hours: $timezone_offset_hours) {
             uptime
             adapters
             users
             command_count_today
             command_count
             history_count
             history_count_today
             }
             }"
            {"timezone_offset_hours" 6}
            )))))
  )
