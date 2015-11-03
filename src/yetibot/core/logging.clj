(ns yetibot.core.logging
  (:require
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util :refer [with-fresh-db]]
    [yetibot.core.db.log :as log]
    [taoensso.timbre.appenders.rolling :refer [make-rolling-appender]]
    [taoensso.timbre
     :as timbre
     :refer [trace debug info warn error fatal spy with-log-level]]))

(timbre/set-level! (get-config :log-level))

; (timbre/set-config! [:appenders :spit :enabled?] true)
; (timbre/set-config! [:shared-appender-config :spit-filename] "/var/log/yetibot/yetibot.log")

;; rolling log files
(timbre/set-config! [:appenders :rolling]
                    (make-rolling-appender {:enabled? true}
                                           {:path "/var/log/yetibot/yetibot.log"
                                            :pattern :daily}))

(defn log-to-db
  [{:keys [ap-config level prefix throwable message] :as args}]
  (with-fresh-db
    (log/create (select-keys args [:level :prefix :message]))))

(defn start []
  ; log to datomic
  #_(timbre/set-config!
      [:appenders :datomic]
      {:doc       "Datomic logger"
       :min-level :info
       :enabled?  true
       :async?    false
       :limit-per-msecs nil ; No rate limit
       :fn #'log-to-db})
  )
