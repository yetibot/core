(ns yetibot.core.test.commands.history
  (:require
    [yetibot.core.db :as db]
    [yetibot.core.config-mutable :refer [reload-config!]]
    [yetibot.core.models.history :as h]
    [yetibot.core.commands.history :refer :all]))

;; we need a database, so load config and start the db
(reload-config!)
(db/start)

(history-cmd {:chat-source {:adapter :test :room "foo"}
              :next-cmds ["scount"]
              :skip-next-n (atom 0)})

(def f (partial h/filter-chat-source :test "foo"))

(history-for-cmd-sequence ["count"] f)

(history-for-cmd-sequence ["random"] f)

(history-for-cmd-sequence ["tail 3"] f)

(history-for-cmd-sequence ["head 3"] f)

(history-for-cmd-sequence ["head"] f)

(history-for-cmd-sequence ["tail"] f)

(history-for-cmd-sequence ["grep 3$"] f)

