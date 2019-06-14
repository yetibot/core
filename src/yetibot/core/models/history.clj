(ns yetibot.core.models.history
  (:require
   [yetibot.core.util.command :refer [config-prefix extract-command]]
   [yetibot.core.db.util :refer [merge-queries
                                 where-eq-any
                                 transform-where-map merge-queries]]
   [yetibot.core.db.history :refer [create query]]
   [clojure.string :refer [join split]]
   [yetibot.core.util.time :as t]
   [clj-time
    [coerce :refer [from-date to-sql-time]]
    [format :refer [formatter unparse]]
    [core :refer [day year month
                  to-time-zone after?
                  default-time-zone now time-zone-for-id date-time utc
                  ago hours days weeks years months]]]
   [clojure.data.codec.base64 :as b64]
   [yetibot.core.models.users :as u]
   [taoensso.timbre :refer [info color-str warn error spy]]))

;;;; read

(defn cursor->id [cursor]
  (try
    (read-string (String. (b64/decode (.getBytes cursor))))
    (catch Exception e
      (throw (ex-info "Invalid cursor"
                      {:cursor cursor})))))

(defn id->cursor [id]
  (String. (b64/encode (.getBytes (str id)))))

(defn build-query
  "Given a bunch of options specific to history return a query data structure"
  [{:keys [cursor
           exclude-private?
           ;; history commands are not included by default
           include-history-commands?
           exclude-yetibot?
           exclude-commands?
           exclude-non-commands?
           search-query
           ;; collection of adapter strings to filter on
           adapters-filter
           ;; collection of channel strings to filter on
           channels-filter
           ;; collection of user name strings to filter on
           users-filter
           ;; datetime
           since-datetime
           until-datetime
           ;; allow caller to pass in additional queries to merge
           extra-query]
    :as options}]
  (info "build query with" (pr-str options))
  (let [id-from-cursor (when cursor (cursor->id cursor))
        queries-to-merge
        ;; Start with an empty vector and conditionally conj a bunch of query
        ;; maps onto it depending on provided options. This vector will then be
        ;; merged into a single combined query map.
        (cond-> []
          exclude-private? (conj {:where/map {:is_private false}})

          ;; by default we exclude history commands, but this isn't super
          ;; cheap, so only exclude history commands if both:
          ;; - exclude-commands? is not true - since this is much cheaper and
          ;;   will cover excluding history anyway
          ;; - include-history-commands? isn't true
          (and
           (not exclude-commands?)
           (not include-history-commands?))
          (conj
           {:where/clause
            "(is_command = ? OR body NOT LIKE ?)"
            :where/args [false
                         (str config-prefix "history%")]})

          exclude-yetibot? (conj {:where/map {:is_yetibot false}})
          exclude-commands? (conj {:where/map {:is_command false}})
          exclude-non-commands? (conj {:where/map {:is_command true}})

          search-query (conj {:where/clause "body ~ ?"
                              :where/args  [search-query]})

          id-from-cursor (conj {:where/clause "id >= ?"
                                :where/args [id-from-cursor]})

          adapters-filter (conj (where-eq-any "chat_source_adapter"
                                              adapters-filter))
          channels-filter (conj (where-eq-any "chat_source_room"
                                              channels-filter))
          users-filter (conj (where-eq-any "user_name" users-filter))
          ;; datetime
          since-datetime
          (conj
           {:where/clause
            "created_at AT TIME ZONE 'UTC' >= ?::TIMESTAMP WITH TIME ZONE"
            :where/args [since-datetime]})

          until-datetime
          (conj
           {:where/clause
            "created_at AT TIME ZONE 'UTC' <= ?::TIMESTAMP WITH TIME ZONE"
            :where/args [(:until options)]}))]
    (info "queries-to-merge" (pr-str queries-to-merge))
    (apply merge-queries (conj queries-to-merge extra-query))))

(defn flatten-one [n] (if (= 1 n) first identity))

(defn count-entities
  [extra-query]
  (-> (query
       (merge-queries
        extra-query
        {:select/clause "COUNT(*) as count"}))
      first
      :count))

(defn head
  [n extra-query]
  ((flatten-one n)
   (query
    (merge-queries extra-query
                   {:limit/clause (str n)}))))

(defn tail
  [n extra-query]
  ((flatten-one n)
   (-> (query
        (merge-queries
         extra-query
         {:order/clause "created_at DESC"
          :limit/clause (str n)}))
       reverse)))

(defn random
  [extra-query]
  (first (query (merge-queries extra-query
                               {;; possibly slow on large tables:
                                :order/clause "random()"
                                :limit/clause "1"}))))

(defn grep [pattern extra-query]
  (query
   (merge-queries
    extra-query
    {:where/clause "body ~ ?"
     :where/args  [pattern]})))

(defn last-chat-for-channel
  "Takes chat source and returns the last chat for the channel.
   `cmd?` is a boolean specifying whether it should be the last yetibot command
   or a normal chat. Useful for `that` commands."
  ([chat-source cmd?] (last-chat-for-channel chat-source cmd? 1))
  ([{:keys [uuid room]} cmd? history-count]
   (query {:limit/clause history-count
           :order/clause "created_at DESC"
           :where/map
           {:is-command cmd?
            :is-yetibot false
            :chat-source-adapter (pr-str uuid)
            :chat-source-room room}})))

;; aggregations

(defn history-count
  []
  (-> (query {:select/clause "COUNT(*) as count"})
      first
      :count))

(defn history-count-today
  ([] (history-count-today 0))
  ([timezone-offset-hours]
   (-> (query {:select/clause "COUNT(*) as count"
               :where/clause
               (str
                 "created_at >= CURRENT_DATE - interval '"
                 timezone-offset-hours " hours'")
               :where/args []})
       first
       :count)))

(defn command-count
  []
  (-> (query {:select/clause "COUNT(*) as count"
              :where/map {:is-command true}})
      first
      :count))

(defn command-count-today
  ([] (command-count-today 0))
  ([timezone-offset-hours]
   (-> (query {:select/clause "COUNT(*) as count"
               :where/map {:is-command true}
               :where/clause
               (str
                 "created_at >= CURRENT_DATE - interval '"
                 timezone-offset-hours " hours'")
               :where/args []})
       first
       :count)))

;; Note: these aren't currently used. If the eventually are, we should figure
;; out a relationally algebraic way to compose in order to avoid querying all
;; cmd or non-cmd items for a given chat-source.
(defn non-cmd-items
  "Return `chat-item` only if it doesn't match any regexes in `history-ignore`"
  [{:keys [uuid room] :as chat-source}]
  (query {:where/map
          {:is-command false
           :chat-source-adapter (pr-str uuid)
           :chat-source-room room}}))

(defn cmd-only-items
  "Return `chat-item` only if it does match any regexes in `history-ignore`"
  [{:keys [uuid room] :as chat-source}]
  (query {:where/map
            {:is-command true
             :chat-source-adapter (pr-str uuid)
             :chat-source-room room}}))

(defn items-for-user
  "Ordered by most recent. Used by the `!` command.
   Filters out all `!` commands to prevent infinite recursion."
  [{:keys [chat-source user cmd? limit]}]
  (let [{:keys [uuid room]} chat-source
        limit (or limit 1)]
    (reverse
      (query
          {:where/clause (str "chat_source_adapter=? AND chat_source_room=?"
                              " AND is_command=? AND body NOT LIKE '!!%'")
           :where/args  [(pr-str uuid) room cmd?]
           :limit/clause limit
           :order/clause "created_at DESC"}))))

;;;; formatting

(defn format-entity [{:keys [created-at user-name body chat-source-room] :as e}]
  ;; devth in #general at 02:16 PM 12/04: !echo foo
  (format "%s in %s at %s: %s"
          user-name chat-source-room
          (t/format-time (from-date created-at)) body))

(defn format-all [entities]
  (if (sequential? entities)
    (map format-entity entities)
    (format-entity entities)))

;;;; write

(defn add [{:keys [chat-source-adapter] :as history-item}]
  (create
    (assoc history-item
           :chat-source-adapter (pr-str chat-source-adapter))))
