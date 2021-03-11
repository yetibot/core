(ns yetibot.core.loader
  (:require
   [taoensso.timbre :refer [debug info warn error]]
   [yetibot.core.config :refer [get-config]]
   [clojure.spec.alpha :as s]
   [yetibot.core.spec :as yspec]
   [clojure.stacktrace :as st]
   [yetibot.core.hooks]
   [clojure.tools.namespace.find :as ns]
   [clojure.java.classpath :as cp]
   [cemerick.pomegranate :refer [add-dependencies]]))

(s/def ::artifact ::yspec/non-blank-string)
(s/def ::version ::yspec/non-blank-string)
(s/def ::name ::yspec/non-blank-string)
(s/def ::url ::yspec/non-blank-string)
(s/def ::repo (s/keys :req-un [::name ::url]))
(s/def ::plugin-config (s/keys :req-un [::artifact ::version]
                               :opt-un [::repo]))
(s/def ::plugins-config (s/map-of keyword? ::plugin-config))

(defn plugins-config []
  (get-config ::plugins-config [:plugins]))

(defn all-namespaces []
  (concat
   (ns/find-namespaces (cp/system-classpath))
   (ns/find-namespaces (cp/classpath))))

(defn find-namespaces [pattern]
  (->> (all-namespaces)
       (filter #(re-matches pattern (str %)))
       (distinct)))

(def yetibot-command-namespaces
  [;; support for e.g.:
   ;; yetibot.commands.*
   ;; yetibot.core.commands.*
   ;; yetibot-pluginname.commands.*
   #"^yetibot(\S*)\.(core\.)?commands.*"
   ;; mycompany.plugins.commands.*
   #"^.*plugins\.commands.*"
   ])

(comment
  (find-namespaces
    (first yetibot-command-namespaces)))

(def yetibot-observer-namespaces
  [#"^yetibot\.(core\.)?observers.*" #"^.*plugins\.observers.*"])

(def yetibot-all-namespaces
  (merge
    (map last [yetibot-command-namespaces
               yetibot-observer-namespaces])
    ; with a negative lookahead assertion
    #"^yetibot\.(.(?!(core)))*"))

(defn load-ns [arg]
  (debug "Loading" arg)
  (try (require arg)
       arg
       (catch Exception e
         (warn "WARNING: problem requiring" arg "hook:" (.getMessage e))
         (st/print-stack-trace (st/root-cause e) 15))))

(defn find-and-load-namespaces
  "Find namespaces matching ns-patterns: a seq of regex patterns. Load the matching
   namespaces and return the seq of matched namespaces."
  [ns-patterns]
  (let [nss (flatten (map find-namespaces ns-patterns))]
    (info "☐ Loading" (count nss) "namespaces matching" ns-patterns)
    (dorun (map load-ns nss))
    (info "☑ Loaded" (count nss) "namespaces matching" ns-patterns)
    nss))

(defn load-commands []
  (find-and-load-namespaces yetibot-command-namespaces))

(defn load-observers []
  (find-and-load-namespaces yetibot-observer-namespaces))

(def default-repositories
  {"clojars" "https://clojars.org/repo"})

;; TODO allow specifying mvn repos in configuration
;; https://github.com/yetibot/yetibot/issues/1038
(defn load-plugins []
  (info "load-plugins" (plugins-config))
  (let [{plugins :value} (plugins-config)]
    (if plugins
      ;; load the plugins 1 by 1 without concurrency
      (run!
       (fn [[_ {:keys [artifact version] :as plugin}]]
         (debug "Loading plugin" (pr-str plugin)
                \newline
                (add-dependencies :coordinates [[(symbol artifact) version]]
                                  :repositories default-repositories)))
       plugins)
      (info "There are no plugins configured to load"))))

(defn load-all
  "Load all plugins, observers, and commands.

   Plugins need to be loaded first so that `load-observers` and `load-commands`
   can find any obs or cmd namespaces they bring"
  []
  (info "load-all")
  (load-plugins)
  (load-observers)
  (load-commands))

(defn reload-all-yetibot
  "Reloads all of YetiBot's namespaces, including plugins. Loading yetibot.core is
   temporarily disabled until we can figure out to unhook and rehook
   handle-campfire-event and handle-cmd"
  []
  ;;; (refresh))
  ;; only load commands and observers
  ;; until https://github.com/devth/yetibot/issues/75 is fixed
  ;;; (find-and-load-namespaces yetibot-all-namespaces))
  (load-all))
