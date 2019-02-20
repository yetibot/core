(ns yetibot.core.db.channel
  "Channel based configuration stores kvs in a table:

   - arbitrary key/values that can be relied on by built in commands (e.g.
     jira-project) or aliases and crons
   - comma-separated list of channels that Yetibot should join on startup (this
     applies mainly to adapters such as IRC that do not remember which channels
     they were in, unlike Slack but we store them for all adapters regardless of
     capabilities); this uses the 'is-member' key
   - comma-separated list of disabled category settings for a channel; this uses
     the 'disabled-categories' key"
  (:require
    [yetibot.core.db.util :as db.util]))

(def schema
  "Mutable channel-specific configuration."
  {:schema/table "channel"
   :schema/specs (into [[:chat-source-adapter :text]
                        [:chat-source-channel :text]
                        [:key :text]
                        [:value :text]]
                       (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))
