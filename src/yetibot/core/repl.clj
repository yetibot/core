(ns yetibot.core.repl
  "Load this namespace when working with yetibot in the REPL or during dev."
  (:require
    [clojure.stacktrace :refer [print-stack-trace]]
    [yetibot.core.db :as db]
    [yetibot.core.logging :as logging] ; enable logging to file
    [yetibot.core.models.users :as users]
    [yetibot.core.webapp.handler :as web]
    [yetibot.core.loader :refer [load-commands-and-observers load-ns]]
    [yetibot.core.adapters.campfire :as cf]
    [yetibot.core.adapters.irc :as irc]
    [yetibot.core.adapters.slack :as slack]))

; use a few non-network commands for testing
(defn load-minimal []
  (require 'yetibot.core.commands.echo :reload)
  (require 'yetibot.core.commands.help :reload)
  (require 'yetibot.core.observers.history :reload)
  (require 'yetibot.core.commands.history :reload)
  (require 'yetibot.core.commands.users :reload)
  (require 'yetibot.core.commands.collections :reload)
  (require 'yetibot.core.observers.users :reload))

(defn load-minimal-with-db []
  (db/repl-start)
  (load-minimal))

(defn start
  "Load a minimal set of commands, start the database and connect to chat adapters"
  []
  (web/start-web-server)
  (load-minimal-with-db)
  (slack/start)
  (cf/start)
  (irc/start))

(defn start-offline
  "Offline repl-driven dev mode"
  []
  (load-minimal-with-db))

(defn stop []
  (slack/stop)
  (irc/stop))

(defn load-all []
  (future
    (load-commands-and-observers)))
