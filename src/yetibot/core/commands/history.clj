(ns yetibot.core.commands.history
  (:require
    [yetibot.core.models.history :as h]
    [clojure.string :refer [join split]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def consumables #{"head" "tail" "count" "grep" "random"})

(defn split-cmd [cmd-with-args] (split cmd-with-args #"\s"))

(defn should-consume-cmd? [cmd-with-args]
  (let [[cmd & _] (split-cmd cmd-with-args)]
    (consumables cmd)))

(defn history-for-cmd-sequence [next-cmds chat-source]
  (let [[next-cmd & args] (split-cmd (first next-cmds))
        ;; only head and tail accept an integer arg
        possible-int-arg (or (when (and (not (empty? args))
                                        (or (= "head" next-cmd)
                                            (= "tail" next-cmd)))
                               (read-string (first args)))
                             1)]
    (condp = next-cmd
      "count" (str (h/count-entities chat-source))
      "head" (h/format-all (h/head chat-source possible-int-arg))
      "tail" (h/format-all (h/tail chat-source possible-int-arg))
      "random" (h/format-all (h/random chat-source))
      "grep" (h/format-all (h/grep chat-source (join " " args)))
      :else nil
      )))

;; `history` can look ahead at next command to see if there's a head/tail, count
;; or grep or possibly another collection operation so it can consume that
;; command and push it down into the database instead of obtaining all history
;; then performing operations on it in memory. If no search operation is
;; provided, history will return only the latest 30 results.
(defn history-cmd
  "history # show chat history"
  {:yb/cat #{:util}}
  [{:keys [chat-source next-cmds skip-next-n]}]
  ;; for now, only look at the first item from `next-cmds`. eventually we may
  ;; support some sort of query combinator that could calculate query for
  ;; multiple steps, like: history | head 30 | grep foo | count
  (let [
        ;; figure out how many commands to consume
        skip-n (count (take-while should-consume-cmd? (take 1 next-cmds)))

        history (if (> skip-n 0)
                  (do
                    (reset! skip-next-n skip-n)
                    (history-for-cmd-sequence (take 1 next-cmds)
                                              chat-source)
                    )
                  ;; default to last 30 items if there were no filters
                  (take 30 (h/format-all
                             (h/history-for-chat-source chat-source))))]
    ;; format
    history
    ))

(cmd-hook #"history"
  _ history-cmd)
