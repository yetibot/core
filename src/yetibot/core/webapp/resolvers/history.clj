(ns yetibot.core.webapp.resolvers.history
  (:require
   [yetibot.core.db.history :refer [query]]
   [yetibot.core.models.history :as history]
   [yetibot.core.db.alias :as db.alias]
   [yetibot.core.db.observe :as db.observe]
   [yetibot.core.db.cron :as db.cron]
   [yetibot.core.commands.uptime :as uptime]
   [yetibot.core.adapters.adapter :as adapter]
   [yetibot.core.models.users :as users]
   [com.walmartlabs.lacinia.executor :refer [selections-seq selects-field?]]
   [taoensso.timbre :refer [error debug info color-str]]))

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
                        :where/args [search_query]})]
    (query (merge {:query/identifiers identity
                   :where/map where-map
                   :limit/clause limit
                   :offset/clause offset
                   :order/clause "created_at DESC"}
                  where-clause))))

(defn history-item-resolver
  [_ {:keys [id] :as args} _]
  (let [where-map {"id" id}]
    (first
     (query (merge {:query/identifiers identity
                    :where/map where-map})))))

(defn adapters-resolver
  [_ _ _]
  (for [[k v] (group-by
               :chat_source_adapter
               (query
                {:query/identifiers identity
                 :where/map {:is_private false}
                 :select/clause
                 "chat_source_adapter, chat_source_room"
                 :group/clause
                 "chat_source_adapter, chat_source_room"
                 :order/clause
                 "chat_source_adapter, chat_source_room"}))]
    {:chat_source_adapter k
     :channels v}))
