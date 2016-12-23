(ns yetibot.core.models.bing-search
  (:require
    [schema.core :as s]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

(defn config [] (get-config {:key s/Str} [:bing :search]))

(defn auth [] {:user "user" :password (-> (config) :value :key)})

(defn configured? [] (contains? (config) :value))

(def endpoint "https://api.datamarket.azure.com/Bing/Search/Image")

(def format-result (juxt :MediaUrl :Title))

(defn image-search [q]
  (let [uri (str endpoint "?" (map-to-query-string
                                {:Query (str \' q \')
                                 :$format "json"}))
        results (get-json uri (auth))]
    (map format-result (-> results :d :results))))
