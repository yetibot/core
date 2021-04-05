(ns yetibot.core.test.adapters.slack
  (:require [yetibot.core.adapters :as adapters]
            [yetibot.core.adapters.adapter :as a]
            [yetibot.core.adapters.slack :as slack]
            [yetibot.core.chat :as chat]
            [clojure.test :as test]))

(defn slack-configs []
  (filter
    (fn [c] (= "slack" (:type c)))
    (vals (adapters/adapters-config))))


(def config (slack/slack-config (last (slack-configs))))

(comment
  ;; replace these with real IDs to try it out
  (slack/entity-with-name-by-id
    config {:channel "C11111114"})
  (slack/entity-with-name-by-id
    config {:channel "G11111111"})
  (slack/entity-with-name-by-id
    config
    {:type "message"
     :channel "D11111111"
     :user "U11111111"
     :text "!echo hi"}))

(test/deftest unencode-message-test
  (test/testing "Only a URL"
    (test/is (= "https://imgflip.com"
           (slack/unencode-message "<https://imgflip.com>"))))
  (test/testing "URL with text after"
    (test/is (= "https://imgflip.com .base-img[src!=''] src"
           (slack/unencode-message "<https://imgflip.com> .base-img[src!=''] src"))))
  (test/testing "URL with text surrounding"
    (test/is (= "Why does slack surround URLs with cruft? Jerk. https://imgflip.com .base-img[src!=''] src"
           (slack/unencode-message "Why does slack surround URLs with cruft? Jerk. <https://imgflip.com> .base-img[src!=''] src"))))
  (test/testing "Mutliple urls"
    (test/is (= "Foo https://imgflip.com bar https://www.google.com"
           (slack/unencode-message "Foo <https://imgflip.com> bar <https://www.google.com>"))))
  (test/testing "Replace Slack's weird @channel and @here encodings"
    (test/is (= (slack/unencode-message "<!here> Slaaaaaaaaaaaaaaack")
           "@here Slaaaaaaaaaaaaaaack")
        "why slack? whyyyyy?")
    (test/is (= (slack/unencode-message "<!channel> also")
           "@channel also")
        "just provide the raw text people")))

(test/deftest adapters-tests
  (comment
    (let [adapter (first (a/active-adapters))]
      (slack/history adapter "G1QD1DNG2")

      (binding [chat/*target* "D0HFDJHA4"]
        (a/send-msg adapter "hi"))
      
      (slack/react adapter "balloon" "D0HFDJHA4"))))

(test/deftest channels-for-last-config
  (comment
    (binding [*config* (last (slack-configs))]
      (slack/list-channels))

    (binding [*config* (last (slack-configs))]
      (slack/channels-cached))

    (binding [*config* (last (slack-configs))]
      (slack/channels))

    (binding [*config* (last (slack-configs))]
      (:groups (slack/list-groups)))
    ))

(test/deftest users
  (-> (a/active-adapters)
      ))

(def ^:dynamic *foo*)

(defprotocol A
  (t [_]))

(defrecord AA []
  A
  (t [_] *foo*))

(defn make-a []
  (binding [*foo* 2]
    (->AA)))

(test/deftest verify-bindings-in-record-instance
  (test/is (instance? clojure.lang.Var$Unbound (t (->AA))))

  (test/is (= 1 (binding [*foo* 1] (t (->AA))))
      "verify bindings stick inside an instance of a protocol")

  (t (make-a))

  (:foo (assoc (make-a) :foo 1)))
