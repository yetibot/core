(ns yetibot.core.test.handler
  (:require
    [yetibot.core.parser :refer [parser]]
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

;; embedded commands


(deftest test-embedded-cmds
  (is
    (=
     (embedded-cmds "`echo your temp:` wonder what the `temp 98101` is")
     [[:expr [:cmd [:words "echo" [:space " "] "your" [:space " "] "temp:"]]]
      [:expr [:cmd [:words "temp" [:space " "] "98101"]]]])
    "embedded-cmds should extract a collection of embedded commands from a string"))



