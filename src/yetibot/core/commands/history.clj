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

   history | grep <query> - uses Postgres' ~ operator to search
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
            extra-queries
            (cond-> []

              ;; by default we always constrain history to the chat-source that
              ;; requested it unless `include-all-channels` was specified
              (not (:include-all-channels options))
              {:where/map
               {:chat-source-adapter (-> chat-source :uuid pr-str)}}

              ;; and unless the user specified `include-all-channels` or
              ;; specific `channels` then we also constrain history to the
              ;; channel that it originated from
              (and (not (:include-all-channels options))
                   (not (:channels options))) (conj
                                               {:where/map
                                                {:chat-source-room
                                                 (:room chat-source)}})

              ;; --channels
              (not (blank? (:channels options)))
              (conj
               (let [cs (split (:channels options)
                               re-comma-with-maybe-whitespace)]
                 {:where/clause
                  (str "("
                       (join " OR "
                             (map (constantly "chat_source_room = ?") cs))
                       ")")
                  :where/args cs}))

              ;; by default we exclude history commands, but this isn't super
              ;; cheap, so only exclude history commands if both:
              ;; --exclude-commands is not true - since this is much cheaper and
              ;;   will cover excluding history anyway
              ;; --include-history-commands isn't true
              (and
               (not (:exclude-commands options))
               (not (:include-history-commands
                     options))) (conj
                                 {:where/clause
                                  "(is_command = ? OR body NOT LIKE ?)"
                                  :where/args [false
                                               (str config-prefix "history%")]})

              (:exclude-yetibot options) (conj
                                          {:where/clause "is_yetibot = ?"
                                           :where/args [false]})

              (:exclude-commands options) (conj
                                           {:where/map {:is_command false}})

              (:exclude-non-commands options) (conj
                                               {:where/map {:is_command true}})

              ;; --user USER1,USER2
              (not (blank? (:user options)))
              (conj
               (let [users (split (:user options)
                                  re-comma-with-maybe-whitespace)]
                 {:where/clause
                  (str "("
                       (join " OR " (map (constantly "user_name = ?") users))
                       ")")
                  :where/args users}))

              ;; --since DATE
              (not (blank? (:since options)))
              (conj
               {:where/clause
                "created_at AT TIME ZONE 'UTC' >= ?::TIMESTAMP WITH TIME ZONE"
                :where/args [(:since options)]})

              ;; --until DATE
              (not (blank? (:until options)))
              (conj
               {:where/clause
                "created_at AT TIME ZONE 'UTC' <= ?::TIMESTAMP WITH TIME ZONE"
                :where/args [(:until options)]}))

            ;; now merge the vector of maps into one single map

            _ (info "extra queries to merge" (pr-str extra-queries))
            extra-query (apply merge-queries extra-queries)
            _ (info "extra query" (pr-str extra-query))

            next-commands (take 1 next-cmds)

            history (if (> skip-n 0)
                      (do
                        (reset! skip-next-n skip-n)
                        (history-for-cmd-sequence next-commands
                                                  extra-query))
                      ;; default to last 30 items if there were no filters
                      (take
                       30 (query extra-query)))]

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
