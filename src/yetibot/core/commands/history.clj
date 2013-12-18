(ns yetibot.core.commands.history
  (:require
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn history-cmd
  "history # show chat history"
  [{:keys [chat-source]}] (h/fmt-items-with-user chat-source))

(cmd-hook #"history"
          _ history-cmd)
