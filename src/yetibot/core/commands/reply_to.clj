(ns yetibot.core.commands.reply-to
  (:require
    [clojure.string :refer [blank?]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.chat :refer [*target* chat-data-structure suppress]]
    [yetibot.core.hooks :refer [cmd-hook]]))

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
        (suppress {})))))

(cmd-hook #"replyto"
  #"(\S+)\s(.+)" reply-to-cmd)
