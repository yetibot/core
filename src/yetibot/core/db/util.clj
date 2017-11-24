(ns yetibot.core.db.util
  (:require
    [yetibot.core.config :refer [get-config]]))

(def config-shape {:url String :table {:prefix String}})

(def default-db-url "postgresql://localhost:5432/yetibot")

(def default-table-prefix "yetibot_")

(defn config []
  (or (:value (get-config config-shape [:db]))
      {:url default-db-url
       :table {:prefix default-table-prefix}}))

(defn qualified-table-name
  [table-name]
  (str (-> (config) :table :prefix) table-name))
