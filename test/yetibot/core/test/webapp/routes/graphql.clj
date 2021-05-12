(ns yetibot.core.test.webapp.routes.graphql
  (:require [yetibot.core.webapp.routes.graphql :refer [graphql]]
            [yetibot.core.loader :as ldr]
            [midje.sweet :refer [=> =not=> fact facts contains]]))

(facts
 (ldr/load-commands)
 "about graphql"
 (fact
  "can run a simple graphql query with expected results and no errors"
  (let [results (graphql "{eval(expr: \"echo foo | echo bar\")}" {})]
    (first (:data results)) => [:eval ["bar foo"]]
    results =not=> (contains {:errors coll?})))
 (fact
  "can run a query with variables with results and no errors"
  (let [results (graphql
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
                 {"timezone_offset_hours" 6})]
    results => (contains {:data coll?})
    results =not=> (contains {:errors coll?})))
 (fact
  "a bad query will return only errors"
  (graphql "{eval(expr:iwillfail)}" {}) => (contains {:errors coll?}))
 (fact
  "can run a valid query, without error, that is not associated with
   a legit command"
  (let [results (graphql "{eval(expr: \"some random text\")}" {})]
    (get-in results [:data :eval]) => coll?
    results =not=> (contains {:errors coll?}))))
