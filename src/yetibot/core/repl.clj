(ns yetibot.core.repl
  "Load this namespace when working with yetibot in the REPL or during dev."
  (:require
    [yetibot.core.init :as init]
    [yetibot.core.chat :as chat]
    [clojure.stacktrace :refer [print-stack-trace]]
    [punk.adapter.jvm :as paj]
    [yetibot.core.db :as db]
    [yetibot.core.logging :as logging] ; enable logging to file
    [yetibot.core.models.users :as users]
    [yetibot.core.monitoring :as monitoring]
    [yetibot.core.webapp.handler :as web]
    [yetibot.core.loader :refer [load-commands-and-observers load-ns]]
    [yetibot.core.adapters :as adapters]))

; use a few non-network commands for dev
(defn load-minimal []
  (require 'yetibot.core.commands.category :reload)
  (require 'yetibot.core.commands.about :reload)
  (require 'yetibot.core.commands.collections :reload)
  (require 'yetibot.core.commands.nil :reload)
  (require 'yetibot.core.commands.default-command :reload)
  (require 'yetibot.core.commands.echo :reload)
  (require 'yetibot.core.commands.error :reload)
  (require 'yetibot.core.commands.help :reload)
  (require 'yetibot.core.commands.history :reload)
  (require 'yetibot.core.commands.observe :reload)
  (require 'yetibot.core.commands.alias :reload)
  (require 'yetibot.core.commands.channel :reload)
  (require 'yetibot.core.commands.users :reload)
  (require 'yetibot.core.commands.that :reload)
  (require 'yetibot.core.commands.render :reload)
  (require 'yetibot.core.observers.users :reload))

(defn load-minimal-with-db []
  (db/start)
  (load-minimal))

(defn start
  "Load a minimal set of commands, start the database and connect to chat adapters"
  []
  (paj/start)
  (logging/start)
  (monitoring/start)
  (init/start-nrepl!)
  (web/start-web-server)
  (db/start)
  (adapters/start)
  ;; commands should be loaded last, just like in yetibot.core.init
  ;; otherwise multiple hooks can get registered somehow
  (load-minimal))

(defn start-web
  []
  (load-minimal-with-db)
  (web/start-web-server))

(defn start-offline
  "Offline repl-driven dev mode"
  []
  (load-minimal-with-db))

(defn stop []
  (paj/stop)
  (web/stop-web-server)
  (init/stop-nrepl!)
  (adapters/stop)
  (monitoring/stop))

(defn reset []
  (stop)
  (start))

(defn load-all []
  (future
    (load-commands-and-observers)))

(defn help
  []
  (println
    "\u001B[37m
     Hello.

     The Yetibot dev REPL is intended to be used during interactive development
     as is commonly practiced in the Clojure community (see
     https://clojure.org/guides/repl/introduction if you want to learn more
     about interactive development with a REPL).

     Here are a few commands it provides:

     (start) - run Yetibot with your config and a small subset of core commands.
     (reset) - useful during dev to reload and restart Yetibot
     (stop) - stop Yetibot - can be useful e.g. to free up the webserver port
     (load-all) - load everything instead of the subset of Yetibot

     Have more questions?

     - Check out the docs at https://yetibot.com
     - Join Slack at https://slack.yetibot.com and ask away!

     HTH! λλλ
     \u001B[m"))
