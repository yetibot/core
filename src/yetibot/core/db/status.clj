(ns yetibot.core.db.status
  (:require
    [yetibot.core.db.util :as db.util]))

(def model-namespace :status)

(def schema
  {:schema/table "status"
   :schema/specs [[:id :serial "PRIMARY KEY"]
                  [:user-id :text]
                  [:chat-source-adapter :text]
                  [:chat-source-room :text]
                  [:status :text]
                  [:created-at :timestamp
                   "NOT NULL"
                   "DEFAULT CURRENT_TIMESTAMP"]]})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))
