(ns yetibot.core.commands.history
  (:require
   [yetibot.core.util.command :refer [config-prefix]]
   [yetibot.core.db.util :refer [merge-queries]]
   [clojure.tools.cli :refer [parse-opts]]
   [taoensso.timbre :refer [debug info color-str]]
   [yetibot.core.models.history :as h]
   [yetibot.core.db.history :refer [query]]
   [clojure.string :refer [blank? join split]]
   [yetibot.core.hooks :refer [cmd-hook]]))

(def consumables #{"head" "tail" "count" "grep" "random"})

(defn split-cmd [cmd-with-args] (split cmd-with-args #"\s"))

(defn should-consume-cmd? [cmd-with-args]
  (let [[cmd & _] (split-cmd cmd-with-args)]
    (consumables cmd)))

(defn history-for-cmd-sequence [next-cmds extra-query]
  (let [[next-cmd & args] (split-cmd (first next-cmds))
        ;; only head and tail accept an integer arg
        possible-int-arg (or (when (and (not (empty? args))
                                        (or (= "head" next-cmd)
                                            (= "tail" next-cmd)))
                               (read-string (first args)))
                             1)]
    (condp = next-cmd
      "count" (str (h/count-entities extra-query))
      "head" (h/head possible-int-arg extra-query)
      "tail" (h/tail possible-int-arg extra-query)
      "random" (h/random extra-query)
      "grep" (h/grep (join " " args) extra-query)
      :else nil
      )))

(def cli-options
  [["-h" "--include-history-commands"]
   ["-y" "--exclude-yetibot"]
   ["-e" "--exclude-commands"]
   ["-n" "--exclude-non-commands"]
   ["-a" "--include-all-channels"]
   ["-c" "--channels CHANNEL1,CHANNEL2"]
   ["-u" "--user USER1,USER2"]
   ["-s" "--since DATE"]
   ["-v" "--until DATE"]])

(def re-comma-with-maybe-whitespace #",\s*")

;; `history` can look ahead at next command to see if there's a head/tail, count
;; or grep or possibly another collection operation so it can consume that
;; command and push it down into the database instead of obtaining all history
;; then performing operations on it in memory. If no search operation is
;; provided, history will return only the latest 30 results.
(defn history-cmd
  "history # show chat history

   By default all history except for `history` commands are not included, since
   they make it too easy for a history command to query itself.

   To include them, use:

   -h --include-history-commands

   To further specify which types of history is excluded use any combination of
   these options:

   -y --exclude-yetibot - excludes yetibot responses
   -e --exclude-commands - excludes command requests by users
   -n --exclude-non-commands - excludes normal chat by users

   For example, to only fetch Yetibot responses:

   history -cn

   To only fetch commands:

   history -ny

   To only fetch Yetibot history:

   history -ne

   By default history will only fetch history for the channel that it was
   requested from, but it can also fetch history across all channels within an
   adapter by specifying:

   -a --include-all-channels

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

   Note: <date> can be specified in a variety of formats by using the `date`
   command, e.g.:

   history --since `date last month`
   history --since `date summer solstice`

   See `help date` for more examples.

   history can be combined with pipes as usual, but it has special support for a
   few collection commands where it will bake the expression into a single SQL
   query instead of trying to naively evaluate in memory:

   history | grep <query> - search history using Postgres ~ operator
   history | tail [<n>] - uses LIMIT n and ORDER_BY
   history | head [<n>] - uses LIMIT n and ORDER_BY
   history | random - uses LIMIT 1 Postgres' ORDER_BY random()
   history | count - uses COUNT(*)"
  {:yb/cat #{:util}}
  [{:keys [match chat-source next-cmds skip-next-n]}]
  (info "history-cmd match" (pr-str match))
  ;; for now, only look at the first item from `next-cmds`. eventually we may
  ;; support some sort of query combinator that could calculate query for
  ;; multiple steps, like: history | head 30 | grep foo | count
  (let [{options :options
         errors :errors
         summary :summary
         :as parsed} (parse-opts (split match #" ") cli-options)]

    (if (seq errors)
      {:result/error (str \newline
                          (join (map #(str "🤔 " % \newline) errors))
                          "Available options are:" \newline
                          summary)}
      (let [;; figure out how many commands to consume
            skip-n (count (take-while should-consume-cmd? (take 1 next-cmds)))

            ;; TODO remove after dev
            _ (info "history options" (color-str :green (pr-str parsed)))

            ;; build up a vector of query maps using provided `options` we'll
            ;; merge these into a single map later

            extra-query {:where/clause
                         "(is_private = ? OR chat_source_room = ?)"
                         ;; private history is only available if the commmand
                         ;; originated from the channel that produced the private
                         ;; history
                         :where/args [false (:room chat-source)]
                         ;; always constrain history to the chat-source that
                         ;; requested it
                         :where/map
                         (merge {:chat_source_adapter
                                 (-> chat-source :uuid pr-str)}

                                ;; unless the user specified
                                ;; `include-all-channels` or specific `channels`
                                ;; then we also constrain history to the channel
                                ;; that it originated from
                                (when (and (not (:include-all-channels options))
                                           (not (:channels options)))
                                  {:chat_source_room (:room chat-source)}))}

            history-query (h/build-query
                           {:include-history-commands?
                            (:include-history-commands options)
                            :exclude-yetibot? (:exclude-yetibot options)
                            :exclude-commands? (:exclude-commands options)
                            :exclude-non-commands? (:exclude-non-commands
                                                    options)
                            :search-query nil
                            :adapters-filter nil
                            :channels-filter (when-not (blank? (:channels
                                                                options))
                                               (split
                                                (:channels options)
                                                re-comma-with-maybe-whitespace))
                            :users-filter (when-not (blank? (:user options))
                                            (split
                                             (:user options)
                                             re-comma-with-maybe-whitespace))
                            :since-datetime (:since options)
                            :until-datetime (:until options)
                            :extra-query extra-query})

            _ (info "history query" (pr-str history-query))

            next-commands (take 1 next-cmds)

            history (if (> skip-n 0)
                      (do
                        (reset! skip-next-n skip-n)
                        (history-for-cmd-sequence next-commands
                                                  history-query))
                      ;; default to last 30 items if there were no filters
                      (take
                       30 (query history-query)))]

        (debug "computed history" (pr-str history))
        ;; format
        (if (= (first next-commands) "count")
          ;; count doesn't need any formatting since it's just a raw number
          history
          ;; everything else needs to be formatted for display
          {:result/value (h/format-all history)
           :result/data history})))))

(cmd-hook #"history"
  _ history-cmd)
