(ns yetibot.core.db.karma
  (:require
   [yetibot.core.db.util :as db.util]))

(def schema {:schema/table "karma"
             :schema/specs (into [[:chat-source-adapter :text]
                                  [:chat-source-room :text]
                                  [:user-id :text "NOT NULL"]
                                  [:points :integer "NOT NULL"]
                                  [:voter-id :text "NOT NULL"]
                                  [:note :text]]
                                 (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))
