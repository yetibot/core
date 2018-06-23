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
                adapter_count
                user_count
                command_count_today
                command_count
                history_count
                history_count_today
                alias_count
                observer_count
                cron_count
              }
             }"
            {"timezone_offset_hours" 6}
            )))))
  )
