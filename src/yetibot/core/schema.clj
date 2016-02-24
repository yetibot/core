(ns yetibot.core.schema
  (:require [clojure.string :as s]
            [schema.core :as sc]))

(def non-empty-str
  (sc/both sc/Str (sc/pred (complement s/blank?))))
