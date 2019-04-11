(ns yetibot.core.webapp.resolvers
  (:require
    [yetibot.core.models.users :as users]
    [yetibot.core.chat :as chat]
    [yetibot.core.models.channel :as channel]
    [yetibot.core.webapp.resolvers.stats :as stats]
    [cuerdas.core :refer [kebab snake]]
    [yetibot.core.adapters.adapter :as adapter]
    [yetibot.core.db.history :as history]
    [yetibot.core.db.alias :as db.alias]
    [yetibot.core.db.observe :as db.observe]
    [yetibot.core.db.cron :as db.cron]
    [yetibot.core.models.karma :as karma]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [taoensso.timbre :refer [error debug info color-str]]))

(def eval-chat-source {:adapter :graphql :uuid :graphql :room "graphql"})
(def eval-user {:username "graphql" :name "graphql" :id "graphql"})

(defn eval-resolver
  [context {:keys [expr] :as args} value]
  (debug "eval-resolver" args)
  ;; stub in a fake chat-source and user so commands that depend on these still
  ;; work
  (let [{:keys [value error]} (handle-unparsed-expr
                                eval-chat-source eval-user expr)
        result (or value error)]
    ;; normalize to always returning a collection, as required by the graphql
    ;; schema
    (if (coll? result)
      result
      [result])))

(comment
  (handle-unparsed-expr
    eval-chat-source
    eval-user
    "echo hi")
  )

(defn adapters-resolver
  [context {:keys [] :as args} value]
  (->>
    @adapter/adapters
    vals
    (map #(hash-map :uuid (name (adapter/uuid %))
                    :platform (adapter/platform-name %)
                    :is_connected (adapter/connected? %)
                    :connection_latency (adapter/connection-latency %)
                    :connection_last_active_timestamp
                    (adapter/connection-last-active-timestamp %)))))

(defn history-resolver
  [context
   {:keys [offset limit chat_source_room chat_source_adapter commands_only
           yetibot_only search_query user_filter channel_filter]
    :as args}
   value]
  (info "history resolver. args" args)
  (let [where-map (merge {"is_private" false}
                         (when commands_only {"is_command" true})
                         (when yetibot_only {"is_yetibot" true}))
        where-clause (when search_query
                       {:where/clause
                        "to_tsvector(body) @@ plainto_tsquery(?)"
                        :where/args [search_query]})
        ]
    (history/query (merge {:query/identifiers identity
                           :where/map where-map
                           :limit/clause limit
                           :offset/clause offset
                           :order/clause "created_at DESC"}
                          where-clause))))

(defn history-item-resolver
  [_ {:keys [id] :as args} _]
  (let [where-map {"id" id}]
    (first 
      (history/query (merge {:query/identifiers identity
                             :where/map where-map})))))

(defn channels-resolver
  [context args value]
  (mapcat
    (fn [adapter]
      (binding [chat/*adapter* adapter]
        (map #(hash-map :name %) (chat/channels))))
    (adapter/active-adapters)))

(def stats-resolver (partial stats/stats-resolver))

(defn users-resolver
  [context {:keys [] :as args} value]
  (map (fn [{:keys [username active? id last-active]}]
         {:username username
          :is_active active?
          :id id
          :last_active last-active})
       (vals @users/users)))

(defn user-resolver
  "Can be used as a top level resolver or as part of a nested resolution"
  [context {:keys [id] :as args} {:keys [user_id] :as value}]
  (let [user-id (or id user_id)]
    (info "user-resolver" user-id)
    (when-let [{:keys [username active? id last-active]}
               (users/get-user-by-id user-id)]
      {:username username
       :is_active active?
       :id id
       :last_active last-active
       :karma (karma/get-score (str "@" id))})))

(defn aliases-resolver
  [context {:keys [] :as args} value]
  (db.alias/query {:query/identifiers identity}))

(defn observers-resolver
  [context {:keys [] :as args} value]
  (db.observe/query {:query/identifiers identity}))

(defn crons-resolver
  [context {:keys [] :as args} value]
  (db.cron/query {:query/identifiers identity}))

(defn karmas-resolver
  [context {:keys [report limit] :as args} value]
  (condp = report
    :SCORES (map (fn [{:keys [user-id score]}]
                   {:user_id user-id
                    :score score})
                 (karma/get-high-scores limit))
    :GIVERS (map (fn [{:keys [voter-id score]}]
                   {:user_id voter-id
                    :score score})
                 (karma/get-high-givers limit))))
