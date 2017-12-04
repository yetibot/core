(ns yetibot.core.db.observe
  (:require
    [yetibot.core.db.util :as db.util]))

(def schema
  {:schema/table "observer"
   :schema/specs [[:id :serial "PRIMARY KEY"]
                  [:user-id :text "NOT NULL"]
                  [:pattern :text]
                  [:user-pattern :text]
                  [:channel-pattern :text]
                  [:event-type :text]
                  [:cmd :text "NOT NULL"]
                  [:created-at :timestamp "NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')"]]})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))
