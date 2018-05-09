(ns yetibot.core.test.handler
  (:require
    [yetibot.core.parser :refer [parser]]
    [yetibot.core.handler :refer :all]
    [yetibot.core.commands.history]
    [yetibot.core.util.command :refer [extract-command]]
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
    (str "test history: " i)
    {:username "yetibot" :id "123"}))


(deftest test-newline-cmd
  (let [multi-str (s/join \newline [1 2 3])]
    (is (= multi-str
        (handle-unparsed-expr (str "echo " multi-str))))))

(deftest test-is-a-command
  (let [prefix "?"
        body (str prefix "command arg1 arg2")]
    (is (= (extract-command body prefix) ["?command arg1 arg2" "command arg1 arg2"]))))

(deftest test-is-not-a-command
  (let [prefix "?"
        body "|command arg1 arg2"]
    (is (nil? (extract-command body prefix)))))

;; this freezes!
#_(handle-unparsed-expr "echo Action ansible.command completed.
                       {\"failed\": false, \"stderr\": \"\", \"return_code\": 0, \"succeeded\": true, \"stdout\": \"si-cluster-zk3 . success >> {\n    \"changed\": false,\n    \"ping\": \"pong\"\n}\n\nsi-cluster-zk2 . success >> {\n    \"changed\": false,\n    \"ping\": \"pong\"\n}\n\nsi-cluster-zk1 . success >> {\n    \"changed\": false,\n    \"ping\": \"pong\"\n}\n\"}\""
                      )
