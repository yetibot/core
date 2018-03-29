(ns yetibot.core.webapp.resolvers
  (:require
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [taoensso.timbre :refer [error debug info color-str]]))

(defn eval-resolver
  [context {:keys [expr] :as args} value]
  (debug "eval-resolver" args)
  (let [result (handle-unparsed-expr expr)]
    (if (coll? result)
      result
      [result])))

(defn adapters-resolver
  []
  ;; return empty for now
  []
  )
