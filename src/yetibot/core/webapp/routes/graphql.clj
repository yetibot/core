(ns yetibot.core.webapp.routes.graphql
  (:require
    [clojure.walk :refer [keywordize-keys]]
    [clojure.edn :as edn]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [com.walmartlabs.lacinia :refer [execute]]
    [com.walmartlabs.lacinia.schema :as lacina.schema]
    [com.walmartlabs.lacinia.util :as lacina.util]
    [compojure.core :refer [defroutes POST OPTIONS]]
    [taoensso.timbre :refer [error debug info color-str]]
    [yetibot.core.webapp.resolvers :as resolvers]))

(defn load-schema!
  []
  (-> (io/resource "graphql-schema.edn")
      slurp
      edn/read-string
      (lacina.util/attach-resolvers {:eval resolvers/eval-resolver
                                     :adapters resolvers/adapters-resolver
                                     :history resolvers/history-resolver
                                     :users resolvers/users-resolver
                                     :stats resolvers/stats-resolver
                                     :aliases resolvers/aliases-resolver
                                     :observers resolvers/observers-resolver
                                     :crons resolvers/crons-resolver
                                     })
      lacina.schema/compile))

;; note this is not reloadable
(def schema (delay (load-schema!)))

;; In the future this may be useful for passing extra context into GraphQL
;; resolvers
(def context {})

(defn graphql
  [query variables]
  (let [keyword-vars (keywordize-keys variables)]
    (debug "graphql" {:query query :variables keyword-vars})
    (execute @schema query keyword-vars context)))

(defroutes graphql-routes
  (POST "/graphql" [query variables] (json/write-str (graphql query variables))))
