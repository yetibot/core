(ns yetibot.core.db.user
  (:require
   [yetibot.core.db.util :as db.util]))

(def schema {:schema/table "user"
             :schema/specs
             (into [[:chat-source-adapter :text]
                    [:id :text "NOT NULL"]
                    [:username :text "NOT NULL"]
                    [:display_name :text "NOT NULL"]
                    ;; should we attempt to keep track of which channels the
                    ;; user is in? 🤔
                    ;; if we wanted to store it as a column here it's have to be
                    ;; some serialized form of a Clojure collection
                    ]
                   (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))
