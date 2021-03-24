(ns yetibot.core.models.default-command
  "Determine which command to fallback to and whether fallback is enabled"
  (:require
    [clojure.string :refer [blank?]]
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-config]]))

(s/def ::config any?)

(defn configured-default-command []
  (or
    (:value (get-config ::config [:default :command]))
    "help"))

(s/def ::text string?)

(defn fallback-help-text-override
  "Optional config, may be nil"
  []
  (:value (get-config ::text [:command :fallback :help :text])))

(s/def ::fallback-commands-enabled-config string?)

(defn fallback-enabled?
  "Determine whether fallback commands are enabled when user enters a command
   that doesn't exist. Default is true.

   In the future this may be channel specific but for now it is global."
  []
  (let [{value :value} (get-config ::fallback-commands-enabled-config
                                   [:command :fallback :enabled])]
    (if-not (blank? value)
      (not (= "false" value))
      ;; enabled by default
      true)))
