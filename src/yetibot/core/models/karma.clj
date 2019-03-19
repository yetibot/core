(ns yetibot.core.models.karma
  (:require
   [yetibot.core.db.karma :as db]
   [clj-time.coerce :as time.coerce]))

(defn add-score-delta!
  [user-id voter-id points note]
  (db/create {:user-id user-id
              :voter-id voter-id
              :points points
              :note note}))

(defn get-score
  [user-id]
  (let [score (-> (db/query {:select/clause "SUM(points) as score"
                             :where/map {:user-id user-id}})
                  first :score)]
    (if (nil? score) 0 score)))

(defn get-notes
  ([user-id] (get-notes user-id 3))
  ([user-id cnt]
   (let [cnt (if (or (<= cnt 0) (> cnt 100)) 3 cnt)]
     (map #(update % :created-at time.coerce/from-date)
          (db/query {:select/clause "note, voter_id, created_at"
                     :where/map {:user-id user-id}
                     :where/clause "note IS NOT NULL AND points > 0"
                     :order/clause "created_at DESC"
                     :limit/clause cnt})))))

(defn get-high-scores
  ([] (get-high-scores 10))
  ([cnt]
   (let [cnt (if (or (<= cnt 0) (> cnt 100)) 10 cnt)]
     (db/query {:select/clause "user_id, SUM(points) as score"
                :group/clause "user_id"
                :having/clause "SUM(points) > 0"
                :order/clause "score DESC"
                :limit/clause cnt}))))

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
  [user-id]
  (doseq [id (map :id (db/query {:select/clause "id"
                                 :where/map {:user-id user-id}}))]
    (db/delete id)))

