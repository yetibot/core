(ns yetibot.core.commands.reply-to
  (:require
    [clojure.string :refer [blank?]]
    [yetibot.core.chat :refer [*target*]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.hooks :refer [cmd-hook suppress]]))

(defn reply-to-cmd
  "replyto <target> # send Yetibot's response to a target other than the current channel."
  {:yb/cat #{:util :async}}
  [{:keys [match chat-source] :as extra}]
  (let [[_ target args] match]
    (if (blank? args)
      nil
      (do
        (info "reply to" target args)
        (binding [*target* target]
          (chat-data-structure args))
        (suppress {:nothing nil})))))

(cmd-hook #"replyto"
  #"(\S+)\s(.+)" reply-to-cmd)
