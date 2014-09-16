(ns yetibot.core.commands.history
  (:require
    [yetibot.core.models.history :as h]
    [clojure.string :refer [split]]
    [yetibot.core.hooks :refer [cmd-hook]]))

;; reference what operations can be done with Datomic:
;; http://docs.datomic.com/query.html

(def consumables #{"head" "tail" "count" "grep" "random"})

(defn should-consume-cmd? [cmd-with-args]
  (let [[cmd & _] (split cmd-with-args #"\s")]
    (consumables cmd)))

;; `history` command should be able to look ahead at next command to see if
;; there's a head/tail, count or grep or possibly another collection operation
;; so it can consume that command and build it into the datomic query instead of
;; obtaining all history then performing operations on it in memory. And if no
;; search operation is provided, history can return only the latest 30 results
(defn history-cmd
  "history # show chat history"
  [{:keys [chat-source next-cmds skip-next-n]}]
  (let [skip-n (count (take-while should-consume-cmd? next-cmds))]
    (when (> skip-n 0) (reset! skip-next-n skip-n))
    ;; todo: implement the consumed commands
    (h/fmt-items-with-user chat-source)))

(cmd-hook #"history"
          _ history-cmd)
