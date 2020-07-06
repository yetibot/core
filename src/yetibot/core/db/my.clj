(ns yetibot.core.db.my
  "User-specific configuration stores kvs in a table:

   This is similar to `yetibot.core.db.channel`, except specific to the *user*
   instead of the *channel*."
  (:require
    [yetibot.core.db.util :as db.util]))

(def schema
  "Mutable user-specific configuration."
  {:schema/table "my"
   :schema/specs (into [[:chat-source-adapter :text]
                        [:chat-source-channel :text]
                        [:user-id :text "NOT NULL"]
                        [:key :text "NOT NULL"]
                        [:value :text "NOT NULL"]]
                       (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))
