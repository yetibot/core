(ns yetibot.core.webapp.route-loader
  (:require
    [clojure.stacktrace :as st]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.loader :as loader]))

(def plugin-route-namespaces
  [#"^.*plugins\.routes.*"])

;; non-async version
;; TODO: remove dupe between loader ns and this
(defn load-ns [arg]
  (info "Loading" arg)
  (try (require arg :reload)
       arg
       (catch Exception e
         (warn "WARNING: problem requiring" arg (.getMessage e))
         (st/print-stack-trace (st/root-cause e) 15))))

(defn find-and-load-namespaces
  "Find namespaces matching ns-patterns: a seq of regex patterns. Load the matching
   namespaces and return the seq of matched namespaces."
  [ns-patterns]
  (let [nss (flatten (map loader/find-namespaces ns-patterns))]
    (dorun (map load-ns nss))
    nss))

(defn load-plugin-routes []
  (info "Loading plugin routes")
  (let [nss (find-and-load-namespaces plugin-route-namespaces)]
    (map (fn [n] @(ns-resolve n 'routes)) nss)))
