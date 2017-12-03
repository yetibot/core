(ns yetibot.core.db.history
  (:require
    [yetibot.core.db.util :as db.util]))

(def schema {:schema/table "history"
             :schema/specs [[:id :serial "PRIMARY KEY"]
                            [:chat-source-adapter :text]
                            [:chat-source-room :text]
                            [:user-id :text]
                            [:user-name :text]
                            [:body :text]
                            [:is-command :boolean]
                            [:created-at :timestamp "NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')"]]})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

;; TODO remove find-where - query is a superset of it
(def find-where (partial db.util/find-where (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))
