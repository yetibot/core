(ns yetibot.core.webapp.handler
  (:require
    [compojure.core :refer [defroutes routes wrap-routes]]
    [org.httpkit.server :refer [run-server]]
    [yetibot.core.webapp.routes.home :refer [home-routes]]
    [yetibot.core.webapp.routes.api :refer [api-routes]]
    [yetibot.core.webapp.routes.graphql :refer [graphql-routes]]
    [yetibot.core.webapp.middleware :as middleware]
    [yetibot.core.webapp.session :as session]
    [yetibot.core.webapp.route-loader :as rl]
    [compojure.route :as route]
    [taoensso.timbre :as timbre]
    [environ.core :refer [env]]
    [clojure.tools.nrepl.server :as nrepl]))

(defonce nrepl-server (atom nil))

(defonce web-server (atom nil))

(defroutes base-routes
  (route/resources "/")
  (route/not-found "Not Found"))

(defn init
  "init will be called once on startup"
  []
  ; (start-nrepl)
  ;; start the expired session cleanup job
  (session/start-cleanup-job!)
  (timbre/info "=[ yetibot.webapp started successfully"
               (when (env :dev) "using the development profile")
               "]="))

(defn destroy
  "destroy will be called when your application shuts down. put any clean up
   code here"
  []
  (timbre/info "yetibot is shutting down...")
  ; (stop-nrepl)
  (timbre/info "shutdown complete!"))

(defn app []
  (let [plugin-routes (vec (rl/load-plugin-routes))
        ; base-routes needs to be very last because it contains not-found
        last-routes (conj plugin-routes base-routes)]
    (-> (apply routes
               home-routes
               api-routes
               graphql-routes
               last-routes)
        middleware/wrap-base)))

;; base-routes

(defn start-web-server []
  (init)
  (let [port (read-string (or (env :port) "3003"))]
    (timbre/info "Starting web server on port" port)
    (reset! web-server
            (run-server (app) {:join? false :daemon? true :port port}))))

(defn stop-web-server []
  (when-not (nil? @web-server)
    (@web-server :timeout 100)
    (reset! web-server nil)))

(defn restart-web-server []
  (stop-web-server)
  (start-web-server))
