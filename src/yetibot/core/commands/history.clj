(ns yetibot.core.commands.history
  (:require
    [taoensso.timbre :refer [info]]
    [yetibot.core.models.history :as h]
    [clojure.string :refer [blank? join split]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def consumables #{"head" "tail" "count" "grep" "random"})

(defn split-cmd [cmd-with-args] (split cmd-with-args #"\s"))

(defn should-consume-cmd? [cmd-with-args]
  (let [[cmd & _] (split-cmd cmd-with-args)]
    (consumables cmd)))

(defn history-for-cmd-sequence [next-cmds chat-source extra-where]
  (let [[next-cmd & args] (split-cmd (first next-cmds))
        ;; only head and tail accept an integer arg
        possible-int-arg (or (when (and (not (empty? args))
                                        (or (= "head" next-cmd)
                                            (= "tail" next-cmd)))
                               (read-string (first args)))
                             1)]
    (condp = next-cmd
      "count" (str (h/count-entities chat-source extra-where))
      "head" (h/format-all (h/head chat-source possible-int-arg extra-where))
      "tail" (h/format-all (h/tail chat-source possible-int-arg extra-where))
      "random" (h/format-all (h/random chat-source extra-where))
      "grep" (h/format-all (h/grep chat-source (join " " args) extra-where))
      :else nil
      )))

;; `history` can look ahead at next command to see if there's a head/tail, count
;; or grep or possibly another collection operation so it can consume that
;; command and push it down into the database instead of obtaining all history
;; then performing operations on it in memory. If no search operation is
;; provided, history will return only the latest 30 results.
(defn history-cmd
  "history [<only-yetibot-history>] # show chat history.

   <only-yetibot-history> is optional. If unspecified all history is included.

   If <only-yetibot-history> is true, only Yetibot output is included.
   If <only-yetibot-history> is false, only non-Yetibot output is included."
  {:yb/cat #{:util}}
  [{:keys [match chat-source next-cmds skip-next-n]}]
  (info "history-cmd match" (pr-str match))
  ;; for now, only look at the first item from `next-cmds`. eventually we may
  ;; support some sort of query combinator that could calculate query for
  ;; multiple steps, like: history | head 30 | grep foo | count
  (let [
        ;; figure out how many commands to consume
        skip-n (count (take-while should-consume-cmd? (take 1 next-cmds)))

        extra-where (if (blank? match)
                      {}
                      {:is-yetibot (= "true" match)})

        history (if (> skip-n 0)
                  (do
                    (reset! skip-next-n skip-n)
                    (history-for-cmd-sequence (take 1 next-cmds)
                                              chat-source
                                              extra-where)
                    )
                  ;; default to last 30 items if there were no filters
                  (take
                    30
                    (h/format-all
                      (h/history-for-chat-source chat-source extra-where))))]
    ;; format
    history
    ))


(cmd-hook #"history"
  #"^true$" history-cmd
  #"^false$" history-cmd
  #"^$" history-cmd)
