(ns yetibot.core.webapp.routes.graphql
  (:require
    [clojure.edn :as edn]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [com.walmartlabs.lacinia :refer [execute]]
    [com.walmartlabs.lacinia.schema :as lacina.schema]
    [com.walmartlabs.lacinia.util :as lacina.util]
    [compojure.core :refer [defroutes POST]]
    [taoensso.timbre :refer [error debug info color-str]]
    [yetibot.core.webapp.resolvers :refer [history-resolver eval-resolver adapters-resolver]]))

(defn load-schema!
  []
  (-> (io/resource "graphql-schema.edn")
      slurp
      edn/read-string
      (lacina.util/attach-resolvers {:eval eval-resolver
                                     :adapters adapters-resolver
                                     :history history-resolver})
      lacina.schema/compile))

(def schema (delay (load-schema!)))

(defn graphql
  [query]
  (debug "graphql" query)
  (execute @schema query nil nil))

(defroutes graphql-routes
  (POST "/graphql" [query] (json/write-str (graphql query))))
