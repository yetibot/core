(ns yetibot.core.db.agent-run
  "In-flight `!agent` runs. A row exists only while a run is running; the run
   deletes its own row on any terminal outcome (success/error/timeout). A row
   left behind means the JVM was killed mid-run (a restart) — those are the runs
   resumed on the next boot."
  (:require [yetibot.core.db.util :as db.util]))

(def schema
  {:schema/table "agent_run"
   :schema/specs (into [[:run-id :text "NOT NULL"]
                        [:request :text "NOT NULL"]
                        [:target :text]
                        [:context-channel :text]
                        [:status-id :text]
                        [:adapter-uuid :text]
                        [:mentions :text]
                        [:on-discord :boolean "NOT NULL" "DEFAULT false"]
                        [:attempts :integer "NOT NULL" "DEFAULT 1"]]
                       (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))
