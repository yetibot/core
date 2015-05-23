(ns yetibot.core.init
  (:require
    [yetibot.core.config :as config]
    [yetibot.core.db :as db]
    [yetibot.core.logging :as log]
    [yetibot.core.loader :refer [load-commands-and-observers]]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [yetibot.core.logo :refer [logo]]
    [yetibot.core.adapters.campfire :as cf]
    [yetibot.core.adapters.irc :as irc]
    [yetibot.core.adapters.slack :as slack]))

(defn welcome-message []
  (println logo))

(defn -main [& args]
  (welcome-message)
  (db/start)
  (log/start)
  (cf/start)
  (irc/start)
  (slack/start)
  (load-commands-and-observers))
