(ns yetibot.core.init
  (:require
    [clojure.stacktrace :refer [print-stack-trace]]
    [yetibot.core.config :as config]
    [yetibot.core.db :as db]
    [taoensso.timbre :refer [info warn]]
    [yetibot.core.logging :as logging]
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
            (info "Trying to start adapter" n)
            (f)
            (catch Exception e
              (warn "Error starting adapter" n (with-out-str (print-stack-trace e)))))))

(defn -main [& args]
  (welcome-message)
  (db/start)
  (logging/start)
  (report-ex #(cf/start) "Campfire")
  (report-ex #(irc/start) "IRC")
  (report-ex #(slack/start) "Slack")
  (load-commands-and-observers))
