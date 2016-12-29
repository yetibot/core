(ns yetibot.core.webapp.views.common
  (:require
    [hiccup.page :refer [include-css include-js html5]]
    [hiccup.element :refer :all]))

(defn title [pt]
  (str "Yetibot 🔥 " pt))

(defn layout [page-title & content]
  (html5
    [:head
     [:title (title "")]
     [:link {:rel "icon", :type "image/png", :href "/favicon-32x32.png", :sizes "32x32"}]
     [:link {:rel "icon", :type "image/png", :href "/favicon-16x16.png", :sizes "16x16"}]
     [:script {:type "text/javascript" :src "https://code.jquery.com/jquery-1.12.0.min.js" }]
     ;; [:script {:type "text/javascript" :src "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0-alpha.2/js/bootstrap.min.js" }]
     [:script {:type "text/javascript" :src "/js/main.js" }]
     (include-css "https://maxcdn.bootstrapcdn.com/font-awesome/4.5.0/css/font-awesome.min.css" )
     (include-css"https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0-alpha.2/css/bootstrap.min.css" )
     (include-css "/css/screen.css")]
    [:body
     content]))
