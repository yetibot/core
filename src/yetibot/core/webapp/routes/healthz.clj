(ns yetibot.core.webapp.routes.healthz
  (:require
   [yetibot.core.db :as db]
   [taoensso.timbre :refer [warn]]
   [yetibot.core.adapters.adapter :as adapter]
   [compojure.core :refer [GET defroutes]]))

(defn fully-operational?
  "To be considered healthy and fully operational, a Yetibot must always:

   - have successfully connected
   - be connected to all configured adapters"
  []
  (and
   @db/connected?
   ;; adapters
   (every?
    (fn [a]
      (or (adapter/connected? a)
          (warn (adapter/uuid a)
                (adapter/platform-name a)
                "is not connected")))
    (adapter/active-adapters))))

(def plain-text {"Content-Type" "text/plain"})

;; Yetibot's health check endpoints are intended for use by health checks for
;; any platform, but suited particularly for Kubernetes liveness and readiness
;; probes:
;; https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/
(defroutes healthz-routes

  ;; intended for readiness probe
  (GET "/healthz/ready" [& _]
    ;; if we can serve traffic, we are "READY"
    {:status 200 :body "OK" :headers plain-text})

  ;; intended for liveness (and/or startup) probes, which restart a container if
  ;; a liveness check fails a configurable threshold
  (GET "/healthz" [& _]
    (if (fully-operational?)
      {:status 200 :body "OK" :headers plain-text}
      {:status 503 :body "Service Unavailable" :headers plain-text})))
