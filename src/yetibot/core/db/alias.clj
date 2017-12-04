(ns yetibot.core.db.alias
  (:require
    [yetibot.core.db.util :as db.util]))

(def model-ns :alias)

(def schema {:schema/table "alias"
             :schema/specs [[:id :serial "PRIMARY KEY"]
                            [:user-id :text "NOT NULL"]
                            [:cmd-name :text "NOT NULL"]
                            [:cmd :text "NOT NULL"]
                            [:created-at :timestamp "NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')"]]})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))
