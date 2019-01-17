(ns yetibot.core.models.default-command
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-spec-config]]))

(s/def :yetibot.config.spec/default-command string?)

(defn configured-default-command []
  (or
    (:value (get-spec-config
              :yetibot.config.spec/default-command
              [:default :command]))
    "help"))
