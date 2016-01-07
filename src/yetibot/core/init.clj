(ns yetibot.core.init
  (:require
    [yetibot.core.chat :as chat]
    [yetibot.core.adapters.init :as ai]
    [clojure.stacktrace :refer [print-stack-trace]]
    [clojure.tools.nrepl.server :refer [start-server stop-server]]
    [yetibot.core.config :as config]
    [yetibot.core.db :as db]
    [taoensso.timbre :refer [info warn]]
    [yetibot.core.logging :as logging]
    [yetibot.core.webapp.handler :as web]
    [yetibot.core.loader :refer [load-commands-and-observers]]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [yetibot.core.logo :refer [logo]])
  (:gen-class))

(defn welcome-message []
  (println logo))

(defn -main [& args]
  (config/reload-config)
  (welcome-message)
  (start-server :port 6789)
  (web/start-web-server)
  (db/start)
  (logging/start)
  (ai/start)
  (load-commands-and-observers))
