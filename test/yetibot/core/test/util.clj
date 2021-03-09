(ns yetibot.core.test.util
  (:require
    [yetibot.core.util :refer :all]
    [cemerick.url :refer [url]]
    [clojure.string :as s]
    [clojure.test :refer :all]
    [midje.sweet :refer [=> fact facts]]))

(fact "An invalid URL"
  (image? "nope") => false)

(fact "Valid image URL with query string"
  (image? "https://i.imgflip.com/2v045r.jpg?foo=bar") => [".jpg" "jpg"])

(fact "Image URL with query string that indicates it's an image"
  (image? "http://www5b.wolframalpha.com/Calculate/MSP/MSP6921ei892gfhh9i9649000058fg83ii266d342i?MSPStoreType=image/gif&s=46&t=.jpg") => true)

(facts "URLs that are not images"
  (image? "https://yetibot.com") => false
  (image? "https://yetibot.com/jpg") => false)

(facts "URLs that are images"
  (image? "https://i.imgflip.com/2v045r.jpg") => [".jpg" "jpg"]
  (image? "https://media1.giphy.com/media/15aGGXfSlat2dP6ohs/giphy.gif") => [".gif" "gif"])
