(ns yetibot.core.test.handler
  (:require
    [yetibot.core.handler :refer :all]
    [yetibot.core.commands.history]
    [instaparse.core :as insta]
    [clojure.test :refer :all]))


;; generate some history

(dotimes [i 10]
  (handle-raw
    {:adapter :test :room "foo"}
    {:id "yetitest"}
    :message
    (str "test history: " i)))


