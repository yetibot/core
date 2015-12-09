(ns yetibot.core.models.bing-search
  (:require
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util :refer [make-config conf-valid?]]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

(defn config [] (get-config :yetibot :models :bing-search))
(defn auth [] {:user "user" :password (:bing-key (config))})
(defn configured? [] (conf-valid? (config)))

(def endpoint "https://api.datamarket.azure.com/Bing/Search/Image")

(def format-result (juxt :MediaUrl :Title))

(defn image-search [q]
  (let [uri (str endpoint "?" (map-to-query-string
                                {:Query (str \' q \')
                                 :$format "json"}))
        results (get-json uri (auth))]
    (map format-result (-> results :d :results))))
