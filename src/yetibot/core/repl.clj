(ns yetibot.core.repl
  "Load this namespace when working with YetiBot in the REPL or during dev."
  (:require
    [yetibot.core.db :refer [repl-start]]
    [yetibot.core.models.users :as users]
    [yetibot.core.loader :refer [load-commands-and-observers load-ns]]))

; use a few non-network commands for testing
(defn load-minimal []
  (require 'yetibot.core.commands.echo :reload)
  (require 'yetibot.core.commands.collections :reload))

(load-minimal)

(repl-start)

(defn load-all []
  (future
    (load-commands-and-observers)))
