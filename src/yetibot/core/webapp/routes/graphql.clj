(ns yetibot.core.webapp.routes.graphql
  (:require
   [clojure.walk :refer [keywordize-keys]]
   [clojure.edn :as edn]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [com.walmartlabs.lacinia :refer [execute]]
   [com.walmartlabs.lacinia.schema :as lacinia.schema]
   [com.walmartlabs.lacinia.util :as lacinia.util]
   [compojure.core :refer [defroutes POST OPTIONS]]
   [taoensso.timbre :refer [error debug info color-str]]
   [yetibot.core.webapp.resolvers :as resolvers]
   [yetibot.core.webapp.resolvers.stats :refer [stats-resolver]]
   [yetibot.core.webapp.resolvers.history :refer [adapter-channels-resolver
                                                  history-resolver
                                                  history-item-resolver]]))

(defn load-schema!
  []
  (-> (io/resource "graphql-schema.edn")
      slurp
      edn/read-string
      (lacinia.util/attach-resolvers
       {:eval resolvers/eval-resolver
        :history history-resolver
        :history_item history-item-resolver
        :users resolvers/users-resolver
        :user resolvers/user-resolver
        :stats stats-resolver
        :aliases resolvers/aliases-resolver
        :observers resolvers/observers-resolver
        :crons resolvers/crons-resolver
        :adapter_channels adapter-channels-resolver
        :adapters resolvers/adapters-resolver
        :karmas resolvers/karmas-resolver})
      lacinia.schema/compile))

;; note this is not reloadable
(def schema (delay (load-schema!)))

;; In the future this may be useful for passing extra context into GraphQL
;; resolvers
(def context {})

(defn graphql
  ([query] (graphql query {}))
  ([query variables]
   (let [keyword-vars (keywordize-keys variables)]
     (debug "graphql" {:query query :variables keyword-vars})
     (execute @schema query keyword-vars context))))

(defroutes graphql-routes
  (POST "/graphql" [query variables] (json/write-str (graphql query variables))))
