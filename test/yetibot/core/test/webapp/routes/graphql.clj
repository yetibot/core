(ns yetibot.core.test.webapp.routes.graphql
  (:require
   [midje.sweet :refer [fact => contains has-prefix just]]
   [yetibot.core.midje :refer [value data error]]
   [yetibot.core.webapp.routes.graphql :refer [graphql]]))

(fact "graphql can evaluate a simple expression"
      (graphql "{eval(expr: \"echo foo | echo bar\")}")
      => {:data {:eval ["bar foo"]}})

(fact "graphql can accept variables"
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
       {"timezone_offset_hours" 6}) =not=> (contains {:errors coll?}))


(fact "graphql can filter history"
  (graphql "
    query {
      history(first: 50, exclude_yetibot: true) {
        page_info {
          total_results
        }
        history {
          id
          chat_source_adapter
          chat_source_room
          command
          correlation_id
          created_at
          user_name
          is_command
          is_yetibot
          body
          user_id
          user_name
        }
      }
    }")
  )
