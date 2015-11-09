(ns yetibot.core.webapp.handler
  (:require [compojure.core :refer [defroutes routes wrap-routes]]

            [org.httpkit.server :refer [run-server]]
            ; [ring.adapter.jetty :refer [run-jetty]]

            [yetibot.core.webapp.routes.home :refer [home-routes]]
            [yetibot.core.webapp.routes.api :refer [api-routes]]
            [yetibot.core.webapp.middleware :as middleware]
            [yetibot.core.webapp.session :as session]
            [compojure.route :as route]
            [taoensso.timbre :as timbre]
            [environ.core :refer [env]]
            [clojure.tools.nrepl.server :as nrepl]))

(defonce nrepl-server (atom nil))

(defroutes base-routes
  (route/resources "/")
  (route/not-found "Not Found"))

; (defn start-nrepl
;   "Start a network repl for debugging when the :repl-port is set in the environment."
;   []
;   (when-let [port (env :repl-port)]
;     (try
;       (reset! nrepl-server (nrepl/start-server :port port))
;       (timbre/info "nREPL server started on port" port)
;       (catch Throwable t
;         (timbre/error "failed to start nREPL" t)))))

; (defn stop-nrepl []
;   (when-let [server @nrepl-server]
;     (nrepl/stop-server server)))


(defn init
  "init will be called once when app is deployed as a servlet on an app server
   such as Tomcat put any initialization code here"
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

(def app
  (-> (routes
        home-routes
        api-routes
        ; (wrap-routes home-routes middleware/wrap-csrf)
        ; (wrap-routes api-routes middleware/wrap-csrf)
        base-routes)
      middleware/wrap-base))

(defn simple-app [request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello World"})

(defn start-web-server []
  (init)
  ; (run-jetty #'simple-app {:join? false :daemon? true :port 3100})
  (run-server #'app {:join? false :daemon? true :port 3000}))

  ; (web-server/serve
  ;   app
  ;   {:port 3000
  ;    :init init
  ;    :destroy destroy})
