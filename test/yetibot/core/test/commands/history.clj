(ns yetibot.core.test.commands.history
  (:require
    [yetibot.core.db :as db]
    [yetibot.core.models.history :as h]
    [yetibot.core.commands.history :refer :all]))

;; we need a database, so load config and start the db
(db/start)

(def chat-source {:adapter :test :room "foo"})

(history-cmd {:chat-source chat-source
              :next-cmds ["scount"]
              :skip-next-n (atom 0)})

(def extra-where {:is-yetibot false})

(history-for-cmd-sequence ["count"] chat-source extra-where)

(history-for-cmd-sequence ["random"] chat-source extra-where)

(history-for-cmd-sequence ["tail 3"] chat-source extra-where)

(history-for-cmd-sequence ["head 3"] chat-source extra-where)

(history-for-cmd-sequence ["head"] chat-source extra-where)

(history-for-cmd-sequence ["tail"] chat-source extra-where)

(history-for-cmd-sequence ["grep 3$"] chat-source extra-where)
