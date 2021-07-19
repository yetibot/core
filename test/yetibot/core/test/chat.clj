(ns yetibot.core.test.chat
  (:require [midje.sweet :refer [facts fact =>]]
            [clojure.string :as s]
            [yetibot.core.chat :as c]))

(facts
 "about contains-image-url-lines?"
 (fact
  "it will return 'false' for strings that do not contain a URL with image
   like chars"
  (c/contains-image-url-lines? "image.jpg") => false
  (c/contains-image-url-lines? "http://image.html") => false
  (c/contains-image-url-lines? "http://image.jpeeeg") => false
  (c/contains-image-url-lines? "http://image.pnnng") => false
  (c/contains-image-url-lines? "http://image.giiif") => false)

 (fact
  "it will return 'true' for strings that do contain a URL with image
   like chars"
  (c/contains-image-url-lines? "http://image.jpg") => true
  (c/contains-image-url-lines? "https://image.jpg") => true
  (c/contains-image-url-lines? "https://image.jpeg") => true
  (c/contains-image-url-lines? "http://image.png") => true
  (c/contains-image-url-lines? "http://image.gif") => true))

(facts
 "about should-send-msg-for-each?"
 (fact
  "it will return 'true' when you pass it a legit chat data stucture and
   a formatted message that has \newlines and URLs that contain image like
   chars"
  (let [data ["look at my picture"
              "http://image.jpg"]]
    (c/should-send-msg-for-each? data (s/join \newline data)) => true)))
