(ns yetibot.core.db.status
  (:refer-clojure :exclude [update])
  (:require
    [datomico.core :as dc]
    [datomico.db :refer [q]]))

;;;; schema

(def model-namespace :status)

(def schema (dc/build-schema model-namespace
                             [[:user-id :string]
                              [:chat-source :string]
                              [:status :string]
                              [:created-at :instant]]))

(dc/create-model-fns model-namespace)
