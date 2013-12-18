(ns yetibot.core.init
  (:require
    [yetibot.core.config :as config]
    [yetibot.core.db :as db]
    [yetibot.core.logging :as log]
    [yetibot.core.loader :refer [load-commands-and-observers]]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [yetibot.core.logo :refer [logo]]
    [yetibot.core.version :refer [version]]
    [yetibot.core.adapters.campfire :as cf]
    [yetibot.core.adapters.irc :as irc]))

(defn welcome-message []
  (println (str "Welcome to YetiBot " version))
  (println logo))

(defn -main [& args]
  (welcome-message)
  (db/start)
  (log/start)
  (cf/start)
  (irc/start)
  (load-commands-and-observers))
