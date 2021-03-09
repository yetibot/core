(ns yetibot.core.test.util
  (:require
    [yetibot.core.util :refer :all]
    [cemerick.url :refer [url]]
    [clojure.string :as s]
    [clojure.test :refer :all]
    [midje.sweet :refer [=> fact facts]]))

(facts "ensures provided collections are sequential"
  (ensure-items-seqential `(1 2 3)) => '(1 2 3)
  (ensure-items-seqential #{1 2 3}) => '(1 3 2)
  (ensure-items-seqential {"one" 1 "two" 2}) => '("one: 1" "two: 2"))

(facts "is map-like if a real hash-map or collection with key:value like items"
  (map-like? {:is "map-like"}) => true
  (map-like? ["is:also" "map:like"]) => true
  (map-like? '("is:also" "map:like")) => true
  (map-like? ["is" "not" "map" "like"]) => false)

(facts "splits hash-maps and map-like collections into nested list [[k v]], else return nil"
  (split-kvs {:easy 1 :to "see"}) => [[:easy 1] [:to "see"]]
  (split-kvs ["key1:value1" "key2:value2"]) => [["key1" "value1"] ["key2" "value2"]]
  (split-kvs ["is" "not" "map" "like"]) => nil)

(facts "splits hash-maps and map-like collections with a function, else return original collection"
  (split-kvs-with first {"key1" "value1" "key2" "value2"}) => ["key1" "key2"]
  (split-kvs-with second ["key1:value1" "key2:value2"]) => ["value1" "value2"]
  (split-kvs-with first ["is" "not" "map" "like"]) => ["is" "not" "map" "like"])

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
