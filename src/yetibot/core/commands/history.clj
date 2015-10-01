(ns yetibot.core.commands.history
  (:require
    [yetibot.core.models.history :as h]
    [clojure.string :refer [join split]]
    [yetibot.core.hooks :refer [cmd-hook]]))

;; reference what operations can be done with Datomic:
;; http://docs.datomic.com/query.html

(def consumables #{"head" "tail" "count" "grep" "random"})

(defn split-cmd [cmd-with-args] (split cmd-with-args #"\s"))

(defn should-consume-cmd? [cmd-with-args]
  (let [[cmd & _] (split-cmd cmd-with-args)]
    (consumables cmd)))

(defn history-for-cmd-sequence [next-cmds chat-source-filter]
  (let [[next-cmd & args] (split-cmd (first next-cmds))
        possible-int-arg (or (when (and (not (empty? args))
                                        (or (= "head" next-cmd) (= "tail" next-cmd)))
                               (read-string (first args)))
                             1)]
    (condp = next-cmd
      "count" (ffirst (h/run (chat-source-filter (h/count-entities))))
      "random" (h/touch-and-fmt (h/run (chat-source-filter (h/random))))
      "head" (h/touch-and-fmt (h/head possible-int-arg (chat-source-filter)))
      "tail" (h/touch-and-fmt (h/tail possible-int-arg (chat-source-filter)))
      "grep" (h/touch-and-fmt (h/run (h/grep (re-pattern (join " " args))))))))


;; `history` command should be able to look ahead at next command to see if
;; there's a head/tail, count or grep or possibly another collection operation
;; so it can consume that command and build it into the datomic query instead of
;; obtaining all history then performing operations on it in memory. And if no
;; search operation is provided, history can return only the latest 30 results
(defn history-cmd
  "history # show chat history"
  [{:keys [chat-source next-cmds skip-next-n]}]
  ;; for now, only look at the first item from `next-cmds`. eventually we may
  ;; support some sort of query combinator that could calculate query for
  ;; multiple steps, like: history | head 30 | grep foo | count
  (let [chat-source-filter (partial h/filter-chat-source
                                    (:adapter chat-source)
                                    (:room chat-source))
        ;; figure out how many commands to consume
        skip-n (count (take-while should-consume-cmd? (take 1 next-cmds)))
        history (if (> skip-n 0)
                  (do
                    (reset! skip-next-n skip-n)
                    (history-for-cmd-sequence (take 1 next-cmds) chat-source-filter))
                  ;; default to last 30 items if there were no filters
                  (h/touch-and-fmt (h/tail 30 (chat-source-filter (h/all-entities)))))]
    ;; format
    history
    ))

(cmd-hook #"history"
  _ history-cmd)
