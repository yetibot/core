(ns yetibot.core.commands.!
  (:require
    [clojure.string :as s]
    [yetibot.core.models.history :as h]
    [yetibot.core.util.command :refer [config-prefix]]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn !-cmd
  "! # execute your last command"
  [{:keys [user chat-source] :as cmd-info}]
  (if-let [last-user-cmd (last (h/items-for-user
                                 {:user user
                                  :chat-source chat-source
                                  :cmd? true
                                  :limit 2}))]
    (let [{:keys [value error data]}
          (handle-unparsed-expr
            chat-source
            user
            ;; drop the prefix
            (subs (:body last-user-cmd) (count config-prefix)))]

      (if error
        {:result/error error}
        #:result{:value value :data data}))
    {:result/error
     (format "I couldn't find any command history for you, %s."
             (:name user))}))

(cmd-hook ["!" #"!"]
          _ !-cmd)
