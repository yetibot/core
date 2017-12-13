(ns yetibot.core.db.history
  (:require
    [yetibot.core.db.util :as db.util]))

(def schema
  {:schema/table "history"
   :schema/specs (into [[:chat-source-adapter :text]
                        [:chat-source-room :text]
                        [:user-id :text]
                        [:user-name :text]
                        [:body :text]
                        [:is-command :boolean]]
                       (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))
