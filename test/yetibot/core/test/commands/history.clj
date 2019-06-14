(ns yetibot.core.test.commands.history
  (:require
   [yetibot.core.db :as db]
   [yetibot.core.midje :refer [value data]]
   [midje.sweet :refer [namespace-state-changes with-state-changes fact =>
                        facts truthy]]
   [yetibot.core.models.history :as h]
   [yetibot.core.db.history :refer [query]]
   [yetibot.core.commands.history :refer :all]))

;; we need a database, so load config and start the db
(defonce loader (db/start))

(def chat-source {:adapter :slack
                  :uuid :test
                  :room "foo"})

(def extra-where
  {:where/map
   {:chat-source-adapter (-> chat-source :uuid pr-str)
    :is-yetibot false}})

(facts

 (fact "count with exclude-yetibot produces the correct call to query"
       (history-cmd {:chat-source chat-source
                     :match "--exclude-yetibot"
                     :next-cmds ["count"]
                     :skip-next-n (atom 0)}) => "336"
       (provided
        (query
         {:where/clause "(is_command = ? OR body NOT LIKE ?) AND (is_private = ? OR chat_source_room = ?)"
          :where/args [false "!history%" false "foo"]
          :where/map {:is_yetibot false
                      :chat_source_adapter ":test"
                      :chat_source_room "foo"}
          :select/clause "COUNT(*) as count"}) => '({:count 336})))

 (fact "--include-history-commands produces the correct query"
       (history-cmd
        {:chat-source chat-source
         :match "--include-history-commands"
         :next-cmds ["head"]
         :skip-next-n (atom 0)}) => (value "test in foo at 02:16 PM 12/04: !echo")
       (provided
        (query
         {:where/clause "(is_private = ? OR chat_source_room = ?)"
          :where/args [false "foo"]
          :where/map {:chat_source_adapter ":test"
                      :chat_source_room "foo"}
          :limit/clause "1"}) => '({:is-command true,
                                    :is-private false,
                                    :user-name "test",
                                    :command "",
                                    :user-id "test",
                                    :is-error false,
                                    :chat-source-room "foo",
                                    :is-private-channel false,
                                    :id 1,
                                    :chat-source-adapter ":test",
                                    :correlation-id nil,
                                    :body "!echo",
                                    :created-at #inst "2017-12-04T22:16:54.367477000-00:00",
                                    :is-yetibot false})))

 (fact "--exclude-yetibot produces the correct query"
       (history-cmd
        {:chat-source chat-source
         :match "--exclude-yetibot"
         :next-cmds ["tail"]
         :skip-next-n (atom 0)}) => (value "test in foo at 05:03 PM 05/13: !poke")
       (provided
        (query
         {:where/clause "(is_command = ? OR body NOT LIKE ?) AND (is_private = ? OR chat_source_room = ?)"
          :where/args [false "!history%" false "foo"]
          :where/map {:is_yetibot false
                      :chat_source_adapter ":test"
                      :chat_source_room "foo"}
          :limit/clause "1"
          :order/clause "created_at DESC"}) =>
        '({:is-command nil,
           :is-private false,
           :user-name "test",
           :command "",
           :user-id "test",
           :is-error false,
           :chat-source-room "foo",
           :is-private-channel false,
           :id 6923,
           :chat-source-adapter ":test",
           :correlation-id nil,
           :body "!poke",
           :created-at #inst "2019-05-14T00:03:02.367149000-00:00",
           :is-yetibot false})))

 (fact "--exclude-commands produces the correct query"
       (history-cmd
        {:chat-source chat-source
         :match "--exclude-commands"
         :next-cmds ["random"]
         :skip-next-n (atom 0)}) => (value "test in foo at 07:45 AM 12/20: test history: 3")
       (provided
        (query
         {:where/clause "(is_private = ? OR chat_source_room = ?)"
          :where/args [false "foo"]
          :where/map {:chat_source_adapter ":test"
                      :chat_source_room "foo"
                      :is_command false}
          :limit/clause "1"
          :order/clause "random()"}) =>
        '({:is-command false,
           :is-private false,
           :user-name "test",
           :command "",
           :user-id "yetitest",
           :is-error false,
           :chat-source-room "foo",
           :is-private-channel false,
           :id 592,
           :chat-source-adapter ":test",
           :correlation-id nil,
           :body "test history: 3",
           :created-at #inst "2017-12-20T15:45:59.261853000-00:00",
           :is-yetibot false})))

 (fact "--exclude-non-commands produces the correct query"
       (history-cmd
        {:chat-source chat-source
         :match "--exclude-non-commands"
         :next-cmds ["random"]
         :skip-next-n (atom 0)}) => (value "test in foo at 02:16 PM 12/04: !echo")
       (provided
        (query
         {:where/clause "(is_command = ? OR body NOT LIKE ?) AND (is_private = ? OR chat_source_room = ?)"
          :where/args [false "!history%" false "foo"]
          :where/map {:is_command true
                      :chat_source_adapter ":test"
                      :chat_source_room "foo"}
          :limit/clause "1"
          :order/clause "random()"}) =>
        '({:is-command true,
           :is-private false,
           :user-name "test",
           :command "",
           :user-id "test",
           :is-error false,
           :chat-source-room "foo",
           :is-private-channel false,
           :id 1,
           :chat-source-adapter ":test",
           :correlation-id nil,
           :body "!echo",
           :created-at #inst "2017-12-04T22:16:54.367477000-00:00",
           :is-yetibot false})))

 (fact "--include-all-channels produces the correct query"
       (history-cmd
        {:chat-source chat-source
         :match "--include-all-channels"
         :next-cmds ["grep foo"]
         :skip-next-n (atom 0)}) =>
       (value '("devth in local at 03:09 PM 03/19: !foo  | echo bar %s baz"
                "devth in local at 03:09 PM 03/19: !foo  | echo bar %s baz"
                "devth in local at 03:09 PM 03/19: !foo  | echo bar %s baz"))
       (provided
        (query
         #:where{:clause "(is_command = ? OR body NOT LIKE ?) AND (is_private = ? OR chat_source_room = ?) AND body ~ ?"
                 :args [false "!history%" false "foo" "foo"]
                 :map {:chat_source_adapter ":test"}}) =>
        '({:is-command true,
           :is-private true,
           :user-name "devth",
           :command "",
           :user-id "U3HSJDD1V",
           :is-error false,
           :chat-source-room "local",
           :is-private-channel false,
           :id 5084,
           :chat-source-adapter ":ybslack",
           :correlation-id "1553008191284-1947291120",
           :body "!foo  | echo bar %s baz",
           :created-at #inst "2019-03-19T22:09:51.297549000-00:00",
           :is-yetibot false}
          {:is-command true,
           :is-private true,
           :user-name "devth",
           :command "",
           :user-id "U3HSJDD1V",
           :is-error false,
           :chat-source-room "local",
           :is-private-channel false,
           :id 5084,
           :chat-source-adapter ":ybslack",
           :correlation-id "1553008191284-1947291120",
           :body "!foo  | echo bar %s baz",
           :created-at #inst "2019-03-19T22:09:51.297549000-00:00",
           :is-yetibot false}
          {:is-command true,
           :is-private true,
           :user-name "devth",
           :command "",
           :user-id "U3HSJDD1V",
           :is-error false,
           :chat-source-room "local",
           :is-private-channel false,
           :id 5164,
           :chat-source-adapter ":ybslack",
           :correlation-id "1553008196553-1947291120",
           :body "!foo  | echo bar %s baz",
           :created-at #inst "2019-03-19T22:09:56.562543000-00:00",
           :is-yetibot false})))

 (fact "--channels produces the correct query"
       (history-cmd
        {:chat-source chat-source
         :match "--channels local"
         :next-cmds ["head"]
         :skip-next-n (atom 0)}) =>
       (value "devth in local at 05:14 PM 02/19: !channel settings")
       (provided
        (query
         {:where/clause "(is_command = ? OR body NOT LIKE ?) AND (chat_source_room = ?) AND (is_private = ? OR chat_source_room = ?)"
          :where/args [false "!history%" "local" false "foo"]
          :where/map {:chat_source_adapter ":test"}
          :limit/clause "1"}) =>
        '({:is-command true,
           :is-private true,
           :user-name "devth",
           :command "",
           :user-id "U3HSJDD1V",
           :is-error false,
           :chat-source-room "local",
           :is-private-channel false,
           :id 3886,
           :chat-source-adapter ":ybslack",
           :correlation-id "1550596458618-791705640",
           :body "!channel settings",
           :created-at #inst "2019-02-20T01:14:18.635678000-00:00",
           :is-yetibot false})))

 (fact "--user USER1,USER2 produces the correct query"
       (history-cmd
        {:chat-source chat-source
         :match "--user devth,yetibot --include-all-channels --include-history-commands"
         :next-cmds ["head"]
         :skip-next-n (atom 0)}) =>
       (value "devth in #obs at 03:50 PM 12/04: !status hi")
       (provided
        (query
         {:where/clause "(user_name = ? OR user_name = ?) AND (is_private = ? OR chat_source_room = ?)"
          :where/args ["devth" "yetibot" false "foo"]
          :where/map {:chat_source_adapter ":test"}
          :limit/clause "1"}) =>
        '({:is-command true,
           :is-private false,
           :user-name "devth",
           :command "",
           :user-id "U3HSJDD1V",
           :is-error false,
           :chat-source-room "#obs",
           :is-private-channel false,
           :id 34,
           :chat-source-adapter ":slack",
           :correlation-id nil,
           :body "!status hi",
           :created-at #inst "2017-12-04T23:50:18.650314000-00:00",
           :is-yetibot false})))

 (fact "--since DATE produces the correct query"
       (history-cmd
        {:chat-source chat-source
         :match "--since 2019-03-20T00:00:00.000Z --include-all-channels --include-history-commands"
         :next-cmds ["tail"]
         :skip-next-n (atom 0)}) =>
       (value "yetibot-devth in local at 05:42 PM 05/16: pd teams # list PagerDuty teams\npd teams <query> # list PagerDuty teams matching <query>\npd users # list PagerDuty users\npd users <query> # list PagerDuty users matching <query>")
       (provided
        (query
         {:where/clause "created_at AT TIME ZONE 'UTC' >= ?::TIMESTAMP WITH TIME ZONE AND (is_private = ? OR chat_source_room = ?)"
          :where/args ["2019-03-20T00:00:00.000Z" false "foo"]
          :where/map {:chat_source_adapter ":test"}
          :limit/clause "1"
          :order/clause "created_at DESC"}) =>
        '({:is-command false,
           :is-private true,
           :user-name "yetibot-devth",
           :command "help pd | grep query",
           :user-id "U4H8NNYR2",
           :is-error false,
           :chat-source-room "local",
           :is-private-channel false,
           :id 7098,
           :chat-source-adapter ":ybslack",
           :correlation-id "1558032124431-1378822926",
           :body
           "pd teams # list PagerDuty teams\npd teams <query> # list PagerDuty teams matching <query>\npd users # list PagerDuty users\npd users <query> # list PagerDuty users matching <query>",
           :created-at #inst "2019-05-17T00:42:04.518674000-00:00",
           :is-yetibot true})))

 (fact "--until DATE produces the correct query"
       (history-cmd
        {:chat-source chat-source
         :match "--until 2019-03-20T00:00:00.000Z --include-all-channels --include-history-commands"
         :next-cmds ["tail"]
         :skip-next-n (atom 0)}) =>
       (value "yetibot-dev in local at 04:28 PM 03/19: Yellowstone, MT (US)\n39.0°F - Clear Sky\nFeels like 35°F\nWinds 1.4 mph WSW")
       (provided
        (query
         {:where/clause "created_at AT TIME ZONE 'UTC' <= ?::TIMESTAMP WITH TIME ZONE AND (is_private = ? OR chat_source_room = ?)"
          :where/args [nil false "foo"]
          :where/map {:chat_source_adapter ":test"}
          :limit/clause "1"
          :order/clause "created_at DESC"}) =>
        '({:is-command false,
           :is-private true,
           :user-name "yetibot-dev",
           :command "weather 59101",
           :user-id "U4H8NNYR2",
           :is-error false,
           :chat-source-room "local",
           :is-private-channel false,
           :id 5242,
           :chat-source-adapter ":ybslack",
           :correlation-id "1553016486830--1020611489",
           :body
           "Yellowstone, MT (US)\n39.0°F - Clear Sky\nFeels like 35°F\nWinds 1.4 mph WSW",
           :created-at #inst "2019-03-19T23:28:07.591446000-00:00",
           :is-yetibot true})))

 (history-for-cmd-sequence ["count"] extra-where)

 (history-for-cmd-sequence ["random"] extra-where)

 (history-for-cmd-sequence ["tail 3"] extra-where)

 (history-for-cmd-sequence ["head 3"] extra-where)

 (history-for-cmd-sequence ["head"] extra-where)

 (history-for-cmd-sequence ["tail"] extra-where)

 (count (history-for-cmd-sequence ["grep 3$"] extra-where)))

