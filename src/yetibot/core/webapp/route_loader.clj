(ns yetibot.core.webapp.route-loader
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.loader :as loader]))

(def plugin-route-namespaces
  [#"^.*plugins\.routes.*"])

(defn load-plugin-routesx []
  (map #(ns-resolve % 'routes)
       (loader/find-and-load-namespaces plugin-route-namespaces)))
