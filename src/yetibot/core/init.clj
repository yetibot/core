(ns yetibot.core.init
  (:require
    [yetibot.core.config :as config]
    [yetibot.core.db :as db]
    [taoensso.timbre :refer [warn]]
    [yetibot.core.logging :as log]
    [yetibot.core.loader :refer [load-commands-and-observers]]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [yetibot.core.logo :refer [logo]]
    [yetibot.core.adapters.campfire :as cf]
    [yetibot.core.adapters.irc :as irc]
    [yetibot.core.adapters.slack :as slack]))

(defn welcome-message []
  (println logo))

(defn report-ex [f n]
  (future (try
            (f)
            (catch Exception e
              (warn "Error starting adapter" n e)))))

(defn -main [& args]
  (welcome-message)
  (db/start)
  (log/start)
  (report-ex #(cf/start) "Campfire")
  (report-ex #(irc/start) "IRC")
  (report-ex #(slack/start) "Slack")
  (load-commands-and-observers))
