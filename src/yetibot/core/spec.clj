(ns yetibot.core.spec
  (:require [clojure.string :as string]
            [clojure.spec.alpha :as s]))

(s/def ::non-blank-string (s/and string?
                                 (complement string/blank?)))
