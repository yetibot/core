(ns yetibot.core.db.observe
  (:refer-clojure :exclude [update])
  (:require [datomico.core :as dc]
            [datomico.db :refer [q]]
            [datomico.action :refer [all where raw-where]]))

(def model-ns :observe)

(def schema (dc/build-schema model-ns
                             [[:user-id :string]
                              [:pattern :string]
                              [:user-pattern :string]
                              [:channel-pattern :string]
                              [:event-type :string]
                              [:cmd :string]]))

(dc/create-model-fns model-ns)
