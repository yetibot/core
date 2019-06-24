(ns yetibot.core.webapp.resolvers.history
  (:require
   [com.walmartlabs.lacinia.executor :refer [selections-tree
                                             selections-seq
                                             selects-field?]]
   [taoensso.timbre :refer [error debug info color-str]]
   [yetibot.core.adapters.adapter :as adapter]
   [yetibot.core.commands.uptime :as uptime]
   [yetibot.core.db.alias :as db.alias]
   [yetibot.core.db.cron :as db.cron]
   [yetibot.core.db.history :refer [query]]
   [yetibot.core.db.observe :as db.observe]
   [yetibot.core.models.history :as history]
   [yetibot.core.models.users :as users]))

(defn history-resolver
  [context
   {limit :first
    :keys [cursor

           adapters_filter
           channels_filter

           include_history_commands
           exclude_yetibot
           exclude_commands
           exclude_non_commands

           search_query
           users_filter

           since_datetime
           until_datetime]
    :as args}
   value]
  (info "history resolver"
        {:args args
         :selections-seq (selections-seq context)
         :selections-tree (selections-tree context)})
  (let [limit (or limit 50)
        extra-query {:order/clause "created_at DESC"
                     ;; fetch one extra record so we can compute the next hash
                     :limit/clause (inc limit)}

        ;; history query without extra-query or cursor for counting
        base-query-options {:exclude-private? true
                            :include-history-commands? include_history_commands
                            :exclude-yetibot? exclude_yetibot
                            :exclude-commands? exclude_commands
                            :exclude-non-commands? exclude_non_commands
                            :search-query search_query
                            :adapters-filter adapters_filter
                            :channels-filter channels_filter
                            :users-filter users_filter
                            :since-datetime since_datetime
                            :until-datetime until_datetime}

        history-query (history/build-query (merge base-query-options
                                                  {:extra-query extra-query
                                                   :cursor cursor}))

        results (query (merge {:query/identifiers identity}
                              history-query))
        has-next-page? (> (count results) limit)
        next-page-cursor (when has-next-page?
                           (-> results
                               last
                               :id
                               history/id->cursor))]

    (info "history-query" (pr-str history-query))

    {;; only take up to the limit since there may be an extra record in the
     ;; result set
     :history (take limit results)
     :page_info {:total_results (when (selects-field?
                                       context :page_info/total_results)
                                  (history/count-entities
                                   (history/build-query base-query-options)))
                 :next_page_cursor next-page-cursor
                 :has_next_page has-next-page?}}))


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
