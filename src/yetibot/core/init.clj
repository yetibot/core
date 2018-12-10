(ns yetibot.core.init
  (:require
    [yetibot.core.chat :as chat]
    [yetibot.core.adapters.init :as ai]
    [clojure.stacktrace :refer [print-stack-trace]]
    [clojure.tools.nrepl.server :as nrepl]
    [yetibot.core.config-mutable :as mconfig]
    [yetibot.core.db :as db]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.logging :as logging]
    [yetibot.core.webapp.handler :as web]
    [yetibot.core.loader :refer [load-commands-and-observers]]
    [yetibot.core.logo :refer [logo]]
    [yetibot.core.config :refer [get-config]]
    [schema.core :as s])
  (:gen-class))

(defn welcome-message! []
  (println logo))

(def nrepl-schema
  {(s/optional-key :port) s/Int})

(defn- config [] (:value (get-config nrepl-schema [:nrepl])))

(def nrepl-port (or (:port (config)) 65432))

(defonce nrepl-server (atom nil))

(defn start-nrepl! []
  (info "Starting nrepl on port" nrepl-port)
  (reset!
    nrepl-server
    ;; Note: if you don't set :bind it defaults to :: which may not work if it
    ;; resolves to an IPV6 stack and you're running in Docker
    (nrepl/start-server :port nrepl-port
                        :bind "localhost")))

(defn stop-nrepl! []
  (info "Stopping nrepl on port" nrepl-port)
  (nrepl/stop-server @nrepl-server))

(defn -main [& args]
  ;; only continue if able to load config
  (if-let [c (mconfig/reload-config!)]
    (do
      (welcome-message!)
      (web/start-web-server)
      (start-nrepl!)
      (db/start)
      (logging/start)
      (ai/start)
      (load-commands-and-observers))
    (do
      (error "Yetibot failed to start: please ensure config is in place at" (mconfig/config-path) " and that it is well-formed (see the log above for details)")
      (shutdown-agents))))
