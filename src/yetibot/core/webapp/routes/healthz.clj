(ns yetibot.core.webapp.routes.healthz
  (:require
    [taoensso.timbre :refer [info warn]]
    [yetibot.core.adapters.adapter :as adapter]
    [compojure.core :refer [GET defroutes routes wrap-routes]]))

(defn healthy? []
  (every?
    (fn [a]
      (or (adapter/connected? a)
          (warn (adapter/uuid a)
                (adapter/platform-name a)
                "is not connected")))
    (adapter/active-adapters)))

(def plain-text {"Content-Type" "text/plain"})

(defroutes healthz-routes
  (GET "/healthz" [& _]
       (if (healthy?)
         {:status 200 :body "OK" :headers plain-text}
         {:status 503 :body "Service Unavailable" :headers plain-text})))
