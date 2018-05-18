(ns yetibot.core.webapp.routes.graphql
  (:require
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
                                     :stats resolvers/stats-resolver
                                     })
      lacina.schema/compile))

(def schema (delay (load-schema!)))

;; In the future this may be useful for passing extra context into GraphQL
;; resolvers
(def context {})

(defn graphql
  [query]
  (debug "graphql" query)
  (execute @schema query nil context))

(defroutes graphql-routes
  ;; most clients perform queries over POST but some initially query the
  ;; endpoint via OPTIONS
  (OPTIONS "/graphql" [] "OPTIONS")
  (POST "/graphql" [query] (json/write-str (graphql query))))
