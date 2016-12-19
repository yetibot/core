(ns yetibot.core.db.log
  (:refer-clojure :exclude [update])
  (:require [datomico.core :as dc]
            [datomico.action :refer [all where raw-where]]))

(def model-ns :log)

(def schema
  (dc/build-schema model-ns [[:level :keyword]
                             [:prefix :string]
                             [:message :string]]))

(dc/create-model-fns model-ns)
