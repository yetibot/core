(ns yetibot.core.models.karma
  (:require
   [yetibot.core.db.karma :as db]
   [clj-time.coerce :as time.coerce]))

(defn add-score-delta!
  [{uuid :uuid room :room} user-id voter-id points note]
  (db/create {:chat-source-adapter (pr-str uuid)
              :chat-source-room room
              :user-id user-id
              :voter-id voter-id
              :points points
              :note note}))

(defn get-score
  [{uuid :uuid room :room} user-id]
  (let [score (-> (db/query {:select/clause "SUM(points) as score"
                             :where/map {:user-id user-id
                                         :chat-source-adapter (pr-str uuid)
                                         :chat-source-room room}})
                  first :score)]
    (if (nil? score) 0 score)))

(defn get-notes
  ([chat-source user-id]
   (get-notes chat-source user-id 3))
  ([{uuid :uuid room :room} user-id cnt]
   (let [cnt (if (or (<= cnt 0) (> cnt 100)) 3 cnt)]
     (map #(update % :created-at time.coerce/from-date)
          (db/query {:select/clause "note, voter_id, created_at"
                     :where/map {:user-id user-id
                                 :chat-source-adapter (pr-str uuid)
                                 :chat-source-room room}
                     :where/clause "note IS NOT NULL AND points > 0"
                     :order/clause "created_at DESC"
                     :limit/clause cnt})))))

(defn get-high-scores
  ([] (get-high-scores 10 nil))
  ([cnt] (get-high-scores cnt nil))
  ([cnt {uuid :uuid room :room :as chat-source}]

   [{:keys [chat-source cnt] :or {cnt 10}}]

   (let [cnt (if (or (<= cnt 0) (> cnt 100)) 10 cnt)]
     (db/query (merge {:select/clause "user_id, SUM(points) as score"
                       :group/clause "user_id"
                       :having/clause "SUM(points) > 0"
                       :order/clause "score DESC"
                       :limit/clause cnt}
                      (when chat-source
                        {:where/map {:chat-source-adapter (pr-str uuid)
                                     :chat-source-room room}}))))))

(defn get-high-givers
  ([] (get-high-givers 10))
  ([cnt]
   (let [cnt (if (or (<= cnt 0) (> cnt 100)) 10 cnt)]
     (db/query {:select/clause "voter_id, SUM(points) as score"
                :group/clause "voter_id"
                :having/clause "SUM(points) > 0"
                :order/clause "score DESC"
                :limit/clause cnt}))))

(defn delete-user!
  "Delete user across all chat sources"
  [user-id]
  (doseq [id (map :id (db/query {:select/clause "id"
                                 :where/map {:user-id user-id}}))]
    (db/delete id)))
