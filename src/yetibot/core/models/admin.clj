(ns yetibot.core.model.admin
  (:require
    [schema.core :as s]
    [clojure.repl :refer :all]
    [clojure.pprint :refer [*print-right-margin* pprint]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.string :refer [split]]))

(defn config [] (get-config {:users [s/Str] :commands [s/Str]} [:admin]))

(defn users [] (set (:value (get-config [s/Str] [:admin :priv]))))

(defn user-is-admin? [{:keys [id]}]
  ((-> (config) :value :users set) id))
