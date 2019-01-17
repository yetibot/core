(ns yetibot.core.util.spec-config
  "Config utilities using clojure.spec"
  (:require
    [clojure.spec.alpha :as s]
    [taoensso.timbre :refer [color-str trace debug info warn error]]
    [yetibot.core.util.config :as util.config]))

;; The map inside this atom can be used to reconstruct a single master spec that
;; represents the entirety of Yetibot configuration.
;;
;; This can then be used to generate a sample configuration or validate
;; configuration.
(defonce paths->specs (atom {}))

(defn master-spec []
  (reduce
    (fn [acc [path spec]]
      spec
      ;; too bad we can't build up a spec in a sane way.
      ;; instead we'll have to PLOP stuff around with s/def LOL
      )
    {}
    @paths->specs
    )
  )


(defn get-config
  "Look up configuration in a config tree.
   Uses clojure.spec to verify the expected shape of the config.

   {:error :invalid-shape
    :explain-data (s/explain-data spec value)
    :spec $spec}

   {:error :not-found :path $path}

   {:value $valid-value}
   "
  [config spec path]
  (swap! paths->specs assoc path spec)
  (let [path (if (coll? path) path [path])]
    (if-let [value (get-in config path)]
      (if (= :clojure.spec.alpha/invalid
             (s/conform spec value))
        {:error :invalid-shape
         :explain-data (s/explain-data spec value)
         :spec spec}
        {:value value})
      ;; not found
      {:error :not-found :path path})))
