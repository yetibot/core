(ns yetibot.core.logging
  (:require
    [schema.core :as s]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util :refer [with-fresh-db]]
    [yetibot.core.db.log :as log]
    [taoensso.timbre.appenders.rolling :refer [make-rolling-appender]]
    [taoensso.timbre
     :as timbre
     :refer [trace debug info warn error fatal spy with-log-level]]))

(def config-schema String)

(defn log-level []
  (let [v (get-config config-schema [:yetibot :log :level])]
    (if (:error v)
      ;; default config level
      :warn
      (keyword (:value v)))))

(timbre/set-level! (log-level))

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
  ; log to datomic - disabled
  #_(timbre/set-config!
      [:appenders :datomic]
      {:doc       "Datomic logger"
       :min-level :info
       :enabled?  true
       :async?    false
       :limit-per-msecs nil ; No rate limit
       :fn #'log-to-db}))
