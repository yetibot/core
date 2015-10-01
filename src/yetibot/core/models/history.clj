(ns yetibot.core.models.history
  (:require
    [yetibot.core.db.history :refer :all]
    [clojure.string :refer [join split]]
    [yetibot.core.models.users :as u]
    [taoensso.timbre :refer [info warn error spy]]
    [datomico.core :as dc]
    [datomic.api :as d]
    [datomico.db :refer [q] :as db]
    [yetibot.core.util :refer [with-fresh-db]]
    [datomico.action :refer [all where raw-where]]))

;;;; read

(defn- history
  "retrieve all history and sort it by transaction instant"
  [] (->>
       (q '[:find ?user-id ?body ?txInstant
            ?chat-source-adapter ?chat-source-room
            :where
            [?tx :db/txInstant ?txInstant]
            [?i :history/user-id ?user-id ?tx]
            [?i :history/chat-source-adapter ?chat-source-adapter ?tx]
            [?i :history/chat-source-room ?chat-source-room ?tx]
            [?i :history/body ?body ?tx]])
       (sort-by (fn [[_ _ inst]] inst))))

(defn items-with-user [chat-source]
  "Retrieve a map of user to chat body"
  (let [hist (history)]
    (for [[user-id body] hist]
      {:user (u/get-user chat-source user-id) :body body})))

(defn fmt-items-with-user [chat-source]
  "Format map of user to chat body as a string"
  (for [m (items-with-user chat-source)]
    (str (-> m :user :name) ": " (:body m))))

(defn items-for-user [{:keys [chat-source user]}]
  (filter #(= (-> % :user :id) (:id user)) (items-with-user chat-source)))

;; helpers

;; new read fns

(defn format-entity [e]
  (join ": " ((juxt :history/user-name :history/body) e)))

(defn grep [re]
  {:find ['?e]
   :where [['?e :history/body '?body]
           [(list re-find re '?body)]]})

(defn all-entities [] '
  {:find [?e]
   :where [[?e :history/body ?body ?tx]
           [?tx :db/txInstant ?ts]]})

(defn head [n query] (take n (sort (q query))))

(defn tail [n query] (take-last n (sort (q query))))

(defn count-entities [] '{:find [(count ?e)]
                       :where [[?e :history/body ?body]]})

(defn random [] '{:find [(rand ?e)] :where [[?e :history/body ?body]]})

(defn run [query] (q query))

(defn filter-chat-source
  ([adapter room] (filter-chat-source adapter room (all-entities)))
  ([adapter room query]
   (update-in query [:where] into [['?e :history/chat-source-room room]
                                   ['?e :history/chat-source-adapter adapter]])))



;; entities

(defn touch-all [eids]
  (->> eids
       (map first)
       sort
       (map db/entity)
       (map d/touch)))

;; scratch space to experiment
(comment
  (with-fresh-db
    db/*db*
    )
  d/seek-datoms
  d/since
  )


(defn touch-and-fmt [eids]
  (->> eids
      touch-all
      (map format-entity)))


;; history filters
(def ^:private cmd-history #"^\!")
(def ^:private non-cmd-history #"^[^!]")

(defn filter-history-by [re last-n]
  (->> (tail last-n (grep re))
       reverse
       touch-all))

;; new efficient alternative to `non-cmd-items` and `cmd-only-items`

(defn last-chat-for-room
  "Takes chat source and returns the last chat for the room.
   `cmd?` is a boolean specifying whether it should be the last yetibot command
   or a normal chat.
   Useful for `that` commands."
  ([chat-source cmd?] (last-chat-for-room chat-source cmd? 1))
  ([{:keys [adapter room]} cmd? history-count]
   (->> (filter-chat-source
          adapter room (grep (if cmd? cmd-history non-cmd-history)))
        (tail history-count))))

(defn non-cmd-items
  "Return `chat-item` only if it doesn't match any regexes in `history-ignore`"
  [chat-source]
  (filter-history-by non-cmd-history 100))

(defn cmd-only-items
  "Return `chat-item` only if it does match any regexes in `history-ignore`"
  [chat-source]
  (filter-history-by cmd-history 100))


;;;; write

(defn add [{:keys [user-id body] :as history-item}]
  (create history-item))
