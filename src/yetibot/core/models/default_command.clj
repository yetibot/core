(ns yetibot.core.models.default-command
  (:require
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]))

(defn configured-default-command []
  (or
    (:value (get-config sch/Any [:default :command]))
    "help"))
