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
    "Home"))

  ; (layout/render
  ;   "home.html" {:docs (-> "docs/docs.md" io/resource slurp)}))

(defroutes home-routes
  ;; Yetibot dashboard handles routing client side, so point known routes to the
  ;; "home" route:
  (GET "/" [] (home-page))
  (GET "/adapters" [] (home-page))
  (GET "/history" [] (home-page))
  (GET "/users" [] (home-page))
  (GET "/aliases" [] (home-page))
  (GET "/observers" [] (home-page))
  (GET "/cron" [] (home-page))
  (GET "/repl" [] (home-page))
  #_(GET "/about" [] (about-page))
  )

