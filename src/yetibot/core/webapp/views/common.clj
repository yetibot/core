(ns yetibot.core.webapp.views.common
  (:require
    [hickory.core :as hickory]
    [hickory.select :as s]
    [hickory.zip :refer [hickory-zip]]
    [hickory.render :refer [hickory-to-html]]
    [clojure.zip :as zip]
    [clojure.java.io :as io]
    [yetibot.core.commands.url :as url]
    [hiccup.page :refer [include-css include-js html5]]
    [hiccup.element :refer :all]))

; Parses index.html from node_modules/yetibot-dashboard/build/index.html,
; then adds a JS snippet and serves as HTML.
(defonce index
  (delay
    (let [index-zip (hickory-zip
                      (hickory/as-hickory
                        (hickory/parse
                          (slurp (io/resource "index.html")))))
          body-zip (s/select-next-loc (s/tag :body) index-zip)
          url (:value (url/config))]
      (-> body-zip
          (zip/append-child
            {:type :element,
             :tag :script,
             :content (str "window.Yetibot=window.Yetibot || {};
                            window.Yetibot.url='" url "';")})
          (zip/root)
          (hickory-to-html)))))

(defn layout
  [page-title & content]
  @index)
