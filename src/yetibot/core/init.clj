(ns yetibot.core.init
  (:require
    [yetibot.core.chat :as chat]
    [yetibot.core.adapters :as adapters]
    [clojure.stacktrace :refer [print-stack-trace]]
    [nrepl.server :as nrepl]
    [yetibot.core.db :as db]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.logging :as logging]
    [yetibot.core.webapp.handler :as web]
    [yetibot.core.loader :refer [load-commands-and-observers]]
    [yetibot.core.logo :refer [logo]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.monitoring :as monitoring]
    [clojure.spec.alpha :as s])
  (:gen-class))

(defn welcome-message! []
  (println logo))

(s/def ::port int?)

(s/def ::nrepl-config (s/keys :opt-un [::port]))

(defn- config [] (:value (get-config ::nrepl-config [:nrepl])))

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
  (do
    (welcome-message!)
    (web/start-web-server)
    (start-nrepl!)
    (db/start)
    (logging/start)
    (monitoring/start)
    (adapters/start)
    (load-commands-and-observers)))
