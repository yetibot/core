(ns yetibot.core.commands.that
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn get-that [chat-source cmd?]
  (info (pr-str chat-source))
  ;; the cmd used to call this will be in history, so if cmd? is true, get the
  ;; last two then get the last of those
  (let [items (if cmd? 2 1)
        result (last (h/last-chat-for-channel chat-source cmd? items))]
    {:result/value (:body result)
     :result/data result}))

(defn that-with-cmd-cmd
  "that cmd # retrieve last command from history"
  {:yb/cat #{:util}}
  [{:keys [chat-source]}]
  (get-that chat-source true))

(defn that-cmd
  "that # retrieve last non-command chat from history"
  {:yb/cat #{:util}}
  [{:keys [chat-source]}]
  (get-that chat-source false))

(cmd-hook #"that"
  #"cmd" that-with-cmd-cmd
  _ that-cmd)
