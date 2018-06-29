(ns yetibot.core.webapp.views.common
  (:require
    [clojure.java.io :as io]
    [yetibot.core.commands.url :as url]
    [hiccup.page :refer [include-css include-js html5]]
    [hiccup.element :refer :all]))


(def main-hash (delay (hash (io/resource "main.js"))))

(def vendor-hash (delay (hash (io/resource "vendor.js"))))

(defn title [pt] (str "Yetibot 🔥 " pt))

(defn layout [page-title & content]
  (let [url (:value (url/config))]
    (html5
      [:head
       [:title (title "")]
       [:link {:rel "icon", :type "image/png", :href "/favicon-32x32.png", :sizes "32x32"}]
       [:link {:rel "icon", :type "image/png", :href "/favicon-16x16.png", :sizes "16x16"}]]
      [:body
       (when url
         [:script "window.Yetibot = window.Yetibot || {};
                   window.Yetibot.url = '" url "';"])
       [:script {:type "text/javascript"
                 :src (str "/vendor.js?x=" @vendor-hash) }]
       [:script {:type "text/javascript"
                 :src (str "/main.js?x=" @main-hash)}]
       content])))
