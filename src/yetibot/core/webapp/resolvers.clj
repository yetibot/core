(ns yetibot.core.webapp.resolvers
  (:require
    [yetibot.core.models.users :as users]
    [yetibot.core.webapp.resolvers.stats :as stats]
    [cuerdas.core :refer [kebab snake]]
    [yetibot.core.adapters.adapter :as adapter]
    [yetibot.core.db.history :as history]
    [yetibot.core.db.alias :as db.alias]
    [yetibot.core.db.observe :as db.observe]
    [yetibot.core.db.cron :as db.cron]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [taoensso.timbre :refer [error debug info color-str]]))

(defn eval-resolver
  [context {:keys [expr] :as args} value]
  (debug "eval-resolver" args)
  (let [result (handle-unparsed-expr expr)]
    (if (coll? result)
      result
      [result])))

(defn adapters-resolver
  [context {:keys [] :as args} value]
  (->>
    @adapter/adapters
    vals
    (map #(hash-map :uuid (name (adapter/uuid %))
                    :platform (adapter/platform-name %)))))

(defn history-resolver
  [context {:keys [offset limit chat_source_room chat_source_adapter] :as args} value]
  (info "history resolver with args" args)
  (history/query {:query/identifiers identity
                  :limit/clause limit
                  :offset/clause offset
                  :order/clause "created_at DESC"}))

(def stats-resolver (partial stats/stats-resolver))

(defn users-resolver
  [context {:keys [] :as args} value]
  (map (fn [{:keys [username active? id last-active]}]
         {:username username
          :is_active active?
          :id id
          :last_active last-active})
       (vals @users/users)))

(defn aliases-resolver
  [context {:keys [] :as args} value]
  (db.alias/query {:query/identifiers identity}))

(defn observers-resolver
  [context {:keys [] :as args} value]
  (db.observe/query {:query/identifiers identity}))

(defn crons-resolver
  [context {:keys [] :as args} value]
  (db.cron/query {:query/identifiers identity}))
