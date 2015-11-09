(ns yetibot.core.webapp.routes.home
  (:require [yetibot.core.webapp.layout :as layout]
            [compojure.core :refer [defroutes GET]]
            [yetibot.core.webapp.views.common :as common]
            [ring.util.http-response :refer [ok]]
            [clojure.java.io :as io]))

(defn home-page []
  (common/layout "Home"))

  ; (layout/render
  ;   "home.html" {:docs (-> "docs/docs.md" io/resource slurp)}))

(defroutes home-routes
  (GET "/" [] (home-page))
  #_(GET "/about" [] (about-page))
  )

