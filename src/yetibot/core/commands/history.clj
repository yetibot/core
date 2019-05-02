(ns yetibot.core.commands.history
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [taoensso.timbre :refer [debug info]]
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
      "head" (h/head chat-source possible-int-arg extra-where)
      "tail" (h/tail chat-source possible-int-arg extra-where)
      "random" (h/random chat-source extra-where)
      "grep" (h/grep chat-source (join " " args) extra-where)
      :else nil
      )))

(def cli-options
  [["-h" "--include-history-commands"]
   ["-y" "--exclude-yetibot"]
   ["-e" "--exclude-commands"]
   ["-n" "--exclude-non-commands"]
   ["-a" "--include-all"]
   ["-c" "--channels CHANNEL1,CHANNEL2"]
   ["-u" "--user USER1,USER2"]
   ["-s" "--since DATE"]
   ["-v" "--until DATE"]])


"history # show chat history

 By default all history except for `history` commands are not included, since
 they make it too easy for a history command to query itself.

 To include them, use:

 -h --include-history-commands

 To further specify which types of history is excluded use any combination of
 these options:

 -y --exclude-yetibot - excludes yetibot responses
 -c --exclude-commands - excludes command requests by users
 -e --exclude-non-commands - excludes normal chat by users

 For example, to only fetch Yetibot responses:

 history -cn

 To only fetch commands:

 history -ny

 To only fetch non-Yetibot history:

 history -nc

 By default history will only fetch history for the channel that it was
 requested from, but it can also fetch history across all channels within a chat
 source by specifying:

 -a --include-all

 You can also specify a channel or channels (comma separated):

 -c --channels

 For example:

 history -c #general,#random

 Search history from a specific user or users (comma separated):

 -u --user

 For example:

 history -u devth

 Search within a specific date range:

 -s --since <date>
 -v --until <date>

 Note: <date> can be specified in a variety of formats, such as:

 - 2018-01-01
 - 2 months ago
 - 2 hours ago

 It uses duckling to parse these from natural language.

 history can be combined with pipes as usual, but it has special support for a
 few collection commands where it will bake the expression into a single SQL
 query instead of trying to naively evaluate in memory:

 history | grep <query> - uses Postgres' ~ operator to search
 history | tail [<n>] - uses LIMIT n and ORDER_BY
 history | head [<n>] - uses LIMIT n and ORDER_BY
 history | random - uses LIMIT 1 Postgres' ORDER_BY random()
 history | count - uses COUNT(*)
 "

;; `history` can look ahead at next command to see if there's a head/tail, count
;; or grep or possibly another collection operation so it can consume that
;; command and push it down into the database instead of obtaining all history
;; then performing operations on it in memory. If no search operation is
;; provided, history will return only the latest 30 results.
(defn history-cmd
  "history [<yetibot-history>] # show chat history.

   <yetibot-history> is optional. If unspecified all history is included.

   If <yetibot-history> is true, only Yetibot output is included.
   If <yetibot-history> is false, only non-Yetibot output is included."
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

        next-commands (take 1 next-cmds)

        history (if (> skip-n 0)
                  (do
                    (reset! skip-next-n skip-n)
                    (history-for-cmd-sequence next-commands
                                              chat-source
                                              extra-where))
                  ;; default to last 30 items if there were no filters
                  (take
                    30 (h/history-for-chat-source chat-source extra-where)))]

    (debug "computed history" (pr-str history))
    ;; format
    (if (= (first next-commands) "count")
      ;; count doesn't need any formatting since it's just a raw number
      history
      ;; everything else needs to be formatted for display
      {:result/value (h/format-all history)
       :result/data history})))

(cmd-hook #"history"
  #"^true$" history-cmd
  #"^false$" history-cmd
  #"^$" history-cmd)
