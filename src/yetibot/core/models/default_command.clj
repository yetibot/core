(ns yetibot.core.models.default-command
  "Determine which command to fallback to and whether fallback is enabled"
  (:require
   [clojure.string :refer [blank?]]
   [clojure.spec.alpha :as s]
   [yetibot.core.config :refer [get-config]]))

(s/def ::config any?)

(defn configured-default-command
  ([] (configured-default-command (get-config ::config [:default :command])))
  ([cfg] (or (:value cfg)
             "help")))

(s/def ::text string?)

(defn fallback-help-text-override
  "Optional config, may be nil"
  ([] (fallback-help-text-override (get-config ::text [:command :fallback :help :text])))
  ([cfg] (:value cfg)))

(s/def ::fallback-commands-enabled-config string?)

(defn fallback-enabled?
  "Determine whether fallback commands are enabled when user enters a command
   that doesn't exist. Default is true.

   In the future this may be channel specific but for now it is global."
  ([] (fallback-enabled? (get-config ::fallback-commands-enabled-config
                                     [:command :fallback :enabled])))
  ([cfg] (let [{value :value} cfg]
           (if-not (blank? value)
             (not (= "false" value))
             ;; enabled by default
             true))))
