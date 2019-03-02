(ns yetibot.core.test.util
  (:require
    [yetibot.core.util :refer :all]
    [cemerick.url :refer [url]]
    [clojure.string :as s]
    [clojure.test :refer :all]))

(defn command-stub
  "command stub" []
  (println "i'm just a stub"))

(defn command-stub2
  "command stub 2" []
  (println "i'm just a stub"))

(def hook-stub `(cmd-hook
                  #"test-hook"
                  #"command" (command-stub)
                  #"other" (command-stub2)))

(defn expand-cmd-hook []
  (clojure.pprint/pprint
    (macroexpand-1 hook-stub )))

(defn run-hook []
  ~(hook-stub))

(deftest image?-test
  (testing "An invalid URL"
    (is (not (image? "nope"))))

  (testing "URLs that aren't images"
    (is (not (image? "https://yetibot.com")))
    (is (not (image? "https://yetibot.com/jpg"))))

  (testing "Valid image URLs"
    (is (image? "https://i.imgflip.com/2v045r.jpg"))
    (is (image? "https://media1.giphy.com/media/15aGGXfSlat2dP6ohs/giphy.gif")))

  (testing "Valid image URL with query string"
    (is (image? "https://i.imgflip.com/2v045r.jpg?foo=bar")))

  (testing "Image URL with query string that indicates it's an image"
    (is (image? "http://www5b.wolframalpha.com/Calculate/MSP/MSP6921ei892gfhh9i9649000058fg83ii266d342i?MSPStoreType=image/gif&s=46&t=.jpg"))))
