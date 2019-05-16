(ns yetibot.core.test.commands.history
  (:require
   [yetibot.core.db :as db]
   [midje.sweet :refer [namespace-state-changes with-state-changes fact =>
                        facts truthy]]
   [yetibot.core.models.history :as h]
   [yetibot.core.db.history :refer [query]]
   [yetibot.core.commands.history :refer :all]))

;; we need a database, so load config and start the db
(defonce loader (db/start))

(def chat-source {:adapter :slack
                  :uuid "test"
                  :room "foo"})

(def extra-where
  {:where/map
   {:chat-source-adapter (-> chat-source :uuid pr-str)
    :is-yetibot false}})

(facts

  (history-cmd {:chat-source chat-source
                :match "-y"
                :next-cmds ["count"]
                :skip-next-n (atom 0)})

  (history-for-cmd-sequence ["count"] extra-where)

  (history-for-cmd-sequence ["random"] extra-where)

  (history-for-cmd-sequence ["tail 3"] extra-where)

  (history-for-cmd-sequence ["head 3"] extra-where)

  (history-for-cmd-sequence ["head"] extra-where)

  (history-for-cmd-sequence ["tail"] extra-where)

  (count (history-for-cmd-sequence ["grep 3$"] extra-where))

  )
