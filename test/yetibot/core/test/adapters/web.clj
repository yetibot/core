(ns yetibot.core.test.adapters.web
  "This can be used to start/stop a Web adapter for the purpose of manual
   testing during development."
  (:require
   [yetibot.core.repl :refer [load-minimal-with-db]]
   [yetibot.core.logging :as logging]
   [midje.sweet :refer [=> fact facts]]
   [yetibot.core.adapters :as adapters]
   [yetibot.core.adapters.adapter :as a]
   [yetibot.core.adapters.web :as web]
   [yetibot.core.chat :as chat]))

(def config
  {:name "yetiweb"})

(comment
  (def adapter
    (web/make-web config))
  )
