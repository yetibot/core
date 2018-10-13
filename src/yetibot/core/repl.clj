(ns yetibot.core.repl
  "Load this namespace when working with yetibot in the REPL or during dev."
  (:require
    [yetibot.core.init :as init]
    [yetibot.core.config-mutable :as mconfig]
    [yetibot.core.chat :as chat]
    [clojure.stacktrace :refer [print-stack-trace]]
    [yetibot.core.db :as db]
    [yetibot.core.logging :as logging] ; enable logging to file
    [yetibot.core.models.users :as users]
    [yetibot.core.webapp.handler :as web]
    [yetibot.core.loader :refer [load-commands-and-observers load-ns]]
    [yetibot.core.adapters.init :as ai]))

; use a few non-network commands for dev
(defn load-minimal []
  (require 'yetibot.core.commands.about :reload)
  (require 'yetibot.core.commands.collections :reload)
  (require 'yetibot.core.commands.nil :reload)
  (require 'yetibot.core.commands.default-command :reload)
  (require 'yetibot.core.commands.echo :reload)
  (require 'yetibot.core.commands.help :reload)
  (require 'yetibot.core.commands.history :reload)
  (require 'yetibot.core.commands.observe :reload)
  (require 'yetibot.core.commands.room :reload)
  (require 'yetibot.core.commands.users :reload)
  (require 'yetibot.core.commands.that :reload)
  (require 'yetibot.core.observers.users :reload))

(defn load-minimal-with-db []
  (db/start)
  (load-minimal))

(defn start
  "Load a minimal set of commands, start the database and connect to chat adapters"
  []
  (logging/start)
  (mconfig/reload-config!)
  (init/start-nrepl!)
  (web/start-web-server)
  (db/start)
  (ai/start)
  ;; commands should be loaded last, just like in yetibot.core.init
  ;; otherwise multiple hooks can get registered somehow
  (load-minimal))

(defn start-web
  []
  (mconfig/reload-config!)
  (load-minimal-with-db)
  (web/start-web-server))

(defn start-offline
  "Offline repl-driven dev mode"
  []
  (mconfig/reload-config!)
  (load-minimal-with-db))

(defn stop []
  (web/stop-web-server)
  (init/stop-nrepl!)
  (ai/stop))

(defn reset []
  (stop)
  (start))

(defn load-all []
  (future
    (load-commands-and-observers)))
