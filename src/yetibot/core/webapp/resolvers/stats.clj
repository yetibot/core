(ns yetibot.core.webapp.resolvers.stats
  (:require
    [yetibot.core.models.history :as history]
    [yetibot.core.db.alias :as db.alias]
    [yetibot.core.db.observe :as db.observe]
    [yetibot.core.db.cron :as db.cron]
    [yetibot.core.commands.uptime :as uptime]
    [yetibot.core.adapters.adapter :as adapter]
    [yetibot.core.models.users :as users]
    [com.walmartlabs.lacinia.executor :refer [selections-seq selects-field?]]
    [taoensso.timbre :refer [error debug info color-str]]))

(defn stats-resolver
  [context {:keys [timezone_offset_hours] :as args} value]
  (info "stats resolver with args" args)
  ;; (info (doall (selections-seq context)))
  (merge
    {:uptime (uptime/format-uptime (uptime/uptime-millis))
     :adapter_count (count @adapter/adapters)
     :user_count (count @users/users)}

    ;; only compute expensive fields when they are requested:

    (when (selects-field? context :stats/history_count)
      {:history_count (history/history-count)})
    (when (selects-field? context :stats/history_count_today)
      {:history_count_today (history/history-count-today
                              timezone_offset_hours)})

    (when (selects-field? context :stats/command_count)
      {:command_count (history/command-count)})
    (when (selects-field? context :stats/command_count_today)
      {:command_count_today (history/command-count-today
                              timezone_offset_hours)})

    (when (selects-field? context :stats/alias_count)
      {:alias_count (db.alias/entity-count)})

    (when (selects-field? context :stats/observer_count)
      {:observer_count (db.observe/entity-count)})

    (when (selects-field? context :stats/cron_count)
      {:cron_count (db.cron/entity-count)})

    ))
