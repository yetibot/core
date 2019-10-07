(ns yetibot.core.monitoring
  (:require [clojure.spec.alpha :as s]
            [metrics.reporters.riemann :as riemann]
            [taoensso.timbre :as log]
            [yetibot.core.config :as config]
            [yetibot.core.spec :as yspec])
  (:import [java.util.concurrent TimeUnit]
           [com.codahale.metrics MetricFilter]))

(s/def ::host ::yspec/non-blank-string)

(s/def ::port pos-int?)

(s/def ::interval pos-int?)

(s/def ::riemann (s/keys :req-un [::host]
                         :opt-un [::port ::interval]))

(s/def ::config (s/keys :req-un [::riemann]))

(defonce riemann-reporter (atom nil))

(defn start
  []
  (let [{:keys [host port interval]} (config/get-config ::config [:monitoring])]
    (if (and host port interval)
      (let [reporter (-> (riemann/make-riemann host (or port 5555))
                         (riemann/reporter {:rate-unit TimeUnit/SECONDS
                                            :duration-unit TimeUnit/MILLISECONDS
                                            :filter MetricFilter/ALL}))]
        (log/info "starting monitoring")
        (reset! riemann-reporter reporter)
        (riemann/start reporter (or interval 1)))
      (log/debug "monitoring is not configured"))))

(defn stop
  []
  (when-let [r @riemann-reporter]
    (riemann/stop r)
    (reset! riemann-reporter nil)))
