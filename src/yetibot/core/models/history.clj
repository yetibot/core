(ns yetibot.core.models.history
  (:require
    [yetibot.core.handler :refer [extract-command]]
    [yetibot.core.db.util :refer [transform-where-map]]
    [yetibot.core.db.history :refer [create query]]
    [clojure.string :refer [join split]]
    [yetibot.core.models.users :as u]
    [taoensso.timbre :refer [info warn error spy]]))

;;;; read

(defn history-for-chat-source
  [{:keys [uuid room] :as chat-source}]
  "Retrieve a map of user to chat body"
  (query {:where/map
          {:chat-source-adapter (pr-str uuid)
           :chat-source-room room}}))

(defn count-entities
  [{:keys [uuid room] :as chat-source}]
  (-> (query
        {:where/map
         {:chat-source-adapter (pr-str uuid)
          :chat-source-room room}
         :select/clause "COUNT(*) as count"})
      first
      :count))

(defn head
  [{:keys [uuid room] :as chat-source} n]
  (query
    {:where/map
     {:chat-source-adapter (pr-str uuid)
      :chat-source-room room}
     :limit/clause (str n)}))

(defn tail
  [{:keys [uuid room] :as chat-source} n]
  (-> (query
        {:where/map
         {:chat-source-adapter (pr-str uuid)
          :chat-source-room room}
         :order/clause "created_at DESC"
         :limit/clause (str n)})
      reverse))

(defn random
  [{:keys [uuid room] :as chat-source}]
  (query
    {:where/map
     {:chat-source-adapter (pr-str uuid)
      :chat-source-room room}
     :order/clause "random()" ;; possibly slow on large tables
     :limit/clause "1"}))

(defn grep [{:keys [uuid room] :as chat-source} pattern]
  (query
    {:where/clause (str "chat_source_adapter=? AND chat_source_room=?"
                        " AND body ~ ?")
     :where/args  [(pr-str uuid) room pattern]}))

(defn last-chat-for-room
  "Takes chat source and returns the last chat for the room.
   `cmd?` is a boolean specifying whether it should be the last yetibot command
   or a normal chat. Useful for `that` commands."
  ([chat-source cmd?] (last-chat-for-room chat-source cmd? 1))
  ([{:keys [uuid room]} cmd? history-count]
   (query {:limit/clause history-count
           :order/clause "created_at DESC"
           :where/map
           {:is-command cmd?
            :chat-source-adapter (pr-str uuid)
            :chat-source-room room}})))

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

(defn format-entity [{:keys [user-name body] :as e}]
  (format "%s: %s" user-name body))

(defn format-all [entities]
  (map format-entity entities))

;;;; write

(defn add [{:keys [body chat-source-adapter] :as history-item}]
  (let [cmd? (boolean (extract-command body))]
    (create
      (assoc history-item
             :is-command cmd?
             :chat-source-adapter (pr-str chat-source-adapter)))))
