(ns yetibot.core.db.history
  (:require
    [yetibot.core.db.util :as db.util]))


(def schema
  "For regular human output from a chat adapter, history is simply recorded.
   For Yetibot responses, the correlating user command is tracked.

   We need the concept of request-id to track
   "
  {:schema/table "history"
   :schema/specs (into [[:chat-source-adapter :text]
                        [:chat-source-room :text]
                        [:user-id :text]
                        [:user-name :text]
                        ;; the command that caused yetibot to respond
                        ;; this should only be set on :is-yetibot true fields as
                        ;; a convenience method for looking up all history that
                        ;; contains a specific command and never set on user
                        ;; input history
                        [:command :text "DEFAULT ''"]
                        ;; used to correlate a user's input command request
                        ;; with yetibot's response
                        [:correlation-id :text]
                        ;;
                        [:body :text]
                        [:is-yetibot :boolean "DEFAULT false"]
                        ;; groups and DMs are considered private
                        [:is-private :boolean "DEFAULT false"]
                        [:is-error :boolean "DEFAULT false"]
                        [:is-command :boolean]]
                       (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))
