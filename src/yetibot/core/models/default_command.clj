(ns yetibot.core.models.default-command
  "Determine which to command to fallback to and whether fallback is enabled"
  (:require
    [clojure.string :refer [blank?]]
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]))

(defn configured-default-command []
  (or
    (:value (get-config sch/Any [:default :command]))
    "help"))

(defn fallback-enabled?
  "Determine whether fallback commands are enabled when user enters a command
   that doesn't exist. Default is true.

   In the future this may be channel specific but for now it is global."
  []
  (let [{value :value} (get-config sch/Str [:command :fallback :enabled])]
    (if-not (blank? value)
      (not (= "false" value))
      ;; enabled by default
      true)))
