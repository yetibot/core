(ns yetibot.core.test.handler
  (:require
    [yetibot.core.parser :refer [parser]]
    [yetibot.core.handler :refer :all]
    [yetibot.core.commands.history]
    [clojure.string :as s]
    [yetibot.core.repl :refer [load-minimal]]
    [instaparse.core :as insta]
    [clojure.test :refer :all]))

(load-minimal)

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
     ;; temp shouldn't be included because it's not a command/alias in the test
     ;; env
     (embedded-cmds "`echo your temp:` wonder what the `temp 98101` is")
     [[:expr [:cmd [:words "echo" [:space " "] "your" [:space " "] "temp:"]]]])
    "embedded-cmds should extract a collection of embedded commands from a string"))

(deftest test-newline-cmd
  (let [multi-str (s/join \newline [1 2 3])]
    (is (= multi-str
        (handle-unparsed-expr (str "echo " multi-str))))))

;; this freezes!
#_(handle-unparsed-expr "echo Action ansible.command completed.
                       {\"failed\": false, \"stderr\": \"\", \"return_code\": 0, \"succeeded\": true, \"stdout\": \"si-cluster-zk3 . success >> {\n    \"changed\": false,\n    \"ping\": \"pong\"\n}\n\nsi-cluster-zk2 . success >> {\n    \"changed\": false,\n    \"ping\": \"pong\"\n}\n\nsi-cluster-zk1 . success >> {\n    \"changed\": false,\n    \"ping\": \"pong\"\n}\n\"}\""
                      )


