(ns yetibot.core.webapp.resolvers
  (:require
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [taoensso.timbre :refer [error debug info color-str]]))

(defn eval-resolver
  [context {:keys [expr] :as args} value]
  (debug "eval-resolver" args)
  (handle-unparsed-expr expr))

(defn adapters-resolver
  []
  ;; return empty for now
  []
  )
