(ns yetibot.core.models.history
  (:require
    [yetibot.core.db.util :refer [merge-queries]]
    [yetibot.core.db.history :refer [create query]]
    [yetibot.core.util.time :as t]
    [yetibot.core.config :refer [get-config]]
    [clj-time
     [coerce :refer [from-date]]]
    [clojure.spec.alpha :as s]))

;;;; read
(s/def ::yetibot-history-disabled string?)
(defn history-enabled
  "Returns true if history is enabled, false otherwise"
  []
  (not= (:value (get-config ::yetibot-history-disabled [:history :disabled]))
        "true"))

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

(comment
  (head 1 {})
  )

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
  [{:keys [uuid room]}]
  (query {:where/map
          {:is-command false
           :chat-source-adapter (pr-str uuid)
           :chat-source-room room}}))

(defn cmd-only-items
  "Return `chat-item` only if it does match any regexes in `history-ignore`"
  [{:keys [uuid room]}]
  (query {:where/map
            {:is-command true
             :chat-source-adapter (pr-str uuid)
             :chat-source-room room}}))

(defn items-for-user
  "Ordered by most recent. Used by the `!` command.
   Filters out all `!` commands to prevent infinite recursion."
  [{:keys [chat-source cmd? limit]}]
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

(defn format-entity [{:keys [created-at user-name body chat-source-room]}]
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
  (when 
    (history-enabled)
    (create
      (assoc history-item
             :chat-source-adapter (pr-str chat-source-adapter)))))
