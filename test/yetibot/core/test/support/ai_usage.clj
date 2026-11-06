(ns yetibot.core.test.support.ai-usage
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [yetibot.core.db :as database]
            [yetibot.core.db.util :as db]
            [yetibot.core.db.ai-usage :as store]))

(def attrs {:provider "fixture" :model "one" :operation "text"
            :secrets ["PRIVATE-KEY"] :prompt "PRIVATE-PROMPT"})

(defn with-store [f]
  (let [config (db/config)
        prefix (str "test_ai_usage_" (System/nanoTime) "_")
        table (str prefix "ai_usage")]
    (with-redefs [db/config (constantly (assoc config :table {:prefix prefix}))
                  database/connected? (atom true)]
      (try
        (database/idempotent-create-table! "ai_usage" (:schema/specs store/schema))
        (f #(jdbc/query (:url config) [(str "SELECT * FROM " table)]) table)
        (finally
          (jdbc/db-do-commands (:url config) (str "DROP TABLE IF EXISTS " table)))))))

(defn metrics [row]
  (vec (json/parse-string (str (:model_usage row)) true)))
