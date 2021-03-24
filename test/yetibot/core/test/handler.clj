(ns yetibot.core.test.handler
  (:require
   [midje.sweet :refer [fact =>]]
   [yetibot.core.handler :refer [handle-raw handle-unparsed-expr]]
   [clojure.string :as s]
   [yetibot.core.repl :refer [load-minimal]]))

(load-minimal)

(comment
  ;; generate some history
  (dotimes [i 10]
    (handle-raw
     {:adapter :test :room "foo"}
     {:id "yetitest"}
     :message
     {:username "yetibot" :id "123"}
     {:body (str "test history: " i)})))

(def multiline-str (s/join \newline [1 2 3]))

(fact
 "Newlines are preserved in command handling"
 (:value (handle-unparsed-expr (str "echo " multiline-str))) => multiline-str)
