(ns yetibot.core.db.history
  (:require [datomico.core :as dc]
            [datomico.action :refer [all where raw-where]]))


;;;; schema

(def model-namespace :history)

(def schema (dc/build-schema model-namespace
                             [[:user-id :string]
                              [:body :string]]))

(dc/create-model-fns model-namespace)
