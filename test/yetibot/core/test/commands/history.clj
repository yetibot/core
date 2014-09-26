(ns yetibot.core.test.commands.history
  (:require
    [yetibot.core.commands.history :refer :all]))

(history-cmd {:chat-source {:adapter :test :room "foo"}
              :next-cmds ["scount"]
              :skip-next-n (atom 0)})

(def f (partial h/filter-chat-source :test "foo"))

(f (h/random))

(history-for-cmd-sequence ["count"] f)

(history-for-cmd-sequence ["random"] f)

(history-for-cmd-sequence ["tail 3"] f)

(history-for-cmd-sequence ["head 3"] f)

(history-for-cmd-sequence ["head"] f)

(history-for-cmd-sequence ["tail"] f)

(history-for-cmd-sequence ["grep 3$"] f)

