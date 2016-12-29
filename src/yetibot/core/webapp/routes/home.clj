(ns yetibot.core.webapp.routes.home
  (:require [yetibot.core.webapp.layout :as layout]
            [compojure.core :refer [defroutes GET]]
            [yetibot.core.version :refer [version]]
            [yetibot.core.webapp.views.common :as common]
            [ring.util.http-response :refer [ok]]
            [hiccup.element :refer [link-to image]]
            [clojure.java.io :as io]))

(defn home-page []
  (common/layout
    "Home"
    [:div.home
     (link-to "http://yetibot.com"
              (image {:class "yeti"} "/img/yeti.png")
              [:h1.version version])]))

  ; (layout/render
  ;   "home.html" {:docs (-> "docs/docs.md" io/resource slurp)}))

(defroutes home-routes
  (GET "/" [] (home-page))
  #_(GET "/about" [] (about-page))
  )

