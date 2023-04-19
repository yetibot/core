(ns yetibot.core.test.util
  (:require
    [yetibot.core.util :as util]
    [midje.sweet :refer [=> fact facts]]))

(fact "a map is transformed into a map-like seq"
  (util/map-to-strs {"one" 1 "two" 2 "three" 3}) => '("one: 1" "two: 2" "three: 3"))

(facts "ensures provided 'items' are collections, and strings are parsed if possible"
  (util/ensure-items-collection '(1 2 3)) => '(1 2 3)
  (util/ensure-items-collection [1 2 3]) => [1 2 3]
  (util/ensure-items-collection {"one" 1 "two" 2}) => '("one: 1" "two: 2")
  (util/ensure-items-collection "one: 1\ntwo: 2") => ["one: 1" "two: 2"]
  (util/ensure-items-collection 123) => nil)

(facts "ensures provided collections are sequential"
  (util/ensure-items-seqential `(1 2 3)) => '(1 2 3)
  (util/ensure-items-seqential #{1 2 3}) => '(1 3 2)
  (util/ensure-items-seqential {"one" 1 "two" 2}) => '("one: 1" "two: 2"))

(facts "is map-like if a real hash-map or collection with key:value like items"
  (util/map-like? {:is "map-like"}) => true
  (util/map-like? ["is:also" "map:like"]) => true
  (util/map-like? '("is:also" "map:like")) => true
  (util/map-like? ["is" "not" "map" "like"]) => false)

(facts "splits hash-maps and map-like collections into nested list [[k v]], else return nil"
  (util/split-kvs {:easy 1 :to "see"}) => [[:easy 1] [:to "see"]]
  (util/split-kvs ["key1:value1" "key2:value2"]) => [["key1" "value1"] ["key2" "value2"]]
  (util/split-kvs ["is" "not" "map" "like"]) => nil)

(facts "splits hash-maps and map-like collections with a function, else return original collection"
  (util/split-kvs-with first {"key1" "value1" "key2" "value2"}) => ["key1" "key2"]
  (util/split-kvs-with second ["key1:value1" "key2:value2"]) => ["value1" "value2"]
  (util/split-kvs-with first ["is" "not" "map" "like"]) => ["is" "not" "map" "like"])

(fact "An invalid URL"
  (util/image? "nope") => false)

(fact "Valid image URL with query string"
  (util/image? "https://i.imgflip.com/2v045r.jpg?foo=bar") => [".jpg" "jpg"])

(fact "Image URL with query string that indicates it's an image"
  (util/image? "http://www5b.wolframalpha.com/Calculate/MSP/MSP6921ei892gfhh9i9649000058fg83ii266d342i?MSPStoreType=image/gif&s=46&t=.jpg") => true)

(fact "Image URL embedded in a query param"
  (util/image? "https://slack-imgs.com/?c=1&o1=ro&url=https%3A%2F%2Fi.imgflip.com%2F7infdj.jpg") => [".jpg" "jpg"])

(facts "URLs that are not images"
  (util/image? "https://yetibot.com") => false
  (util/image? "https://yetibot.com/jpg") => false)

(facts "URLs that are images"
  (util/image? "https://i.imgflip.com/2v045r.jpg") => [".jpg" "jpg"]
  (util/image? "https://media1.giphy.com/media/15aGGXfSlat2dP6ohs/giphy.gif") => [".gif" "gif"])
