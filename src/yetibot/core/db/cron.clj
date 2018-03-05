(ns yetibot.core.db.cron
  (:require
    [yetibot.core.db.util :as db.util]))

(def schema
  {:schema/table "cron"
   :schema/specs (into [[:chat-source-adapter :text]
                        [:chat-source-room :text]
                        [:user-id :text]
                        [:schedule :text "NOT NULL"]
                        [:cmd :text "NOT NULL"]]
                       (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))

