(ns yetibot.core.test.adapters.slack
  (:require
    [yetibot.core.adapters.adapter :as a]
    [yetibot.core.adapters.init :as ai]
    [yetibot.core.adapters.slack :refer :all]
    [clj-slack
     [users :as slack-users]
     [chat :as slack-chat]
     [channels :as channels]
     [groups :as groups]
     [rtm :as rtm]]
    [yetibot.core.chat :as chat]
    [clojure.test :refer :all]))


(defn slack-configs []
  (filter
    (fn [c] (= :slack (:type c)))
    (ai/adapters-config)))


(def config (slack-config (last (slack-configs))))

(comment
  ;; replace these with real IDs to try it out
  (entity-with-name-by-id
    config {:channel "C11111114"})
  (entity-with-name-by-id
    config {:channel "G11111111"})
  (entity-with-name-by-id
    config
    {:type "message"
     :channel "D11111111"
     :user "U11111111"
     :text "!echo hi"}))

(deftest unencode-message-test
  (testing "Only a URL"
    (is (= "https://imgflip.com"
           (unencode-message "<https://imgflip.com>"))))
  (testing "URL with text after"
    (is (= "https://imgflip.com .base-img[src!=''] src"
           (unencode-message "<https://imgflip.com> .base-img[src!=''] src"))))
  (testing "URL with text surrounding"
    (is (= "Why does slack surround URLs with cruft? Jerk. https://imgflip.com .base-img[src!=''] src"
           (unencode-message "Why does slack surround URLs with cruft? Jerk. <https://imgflip.com> .base-img[src!=''] src"))))
  (testing "Mutliple urls"
    (is (= "Foo https://imgflip.com bar https://www.google.com"
           (unencode-message "Foo <https://imgflip.com> bar <https://www.google.com>")))))


(deftest rooms-for-last-config
  (comment
    (binding [*config* (last (slack-configs))]
      (list-channels))

    (binding [*config* (last (slack-configs))]
      (channels-cached))

    (binding [*config* (last (slack-configs))]
      (rooms))

    (binding [*config* (last (slack-configs))]
      (:groups (list-groups)))
    ))

(deftest users
  (-> (a/active-adapters)
      ))

(def ^:dynamic *foo*)

(defprotocol A
  (t [_]))

(defrecord AA []
  A
  (t [_] *foo*))

(deftest verify-bindings-in-record-instance
  (is (instance? clojure.lang.Var$Unbound (t (->AA))))

  (is (= 1 (binding [*foo* 1] (t (->AA))))
      "verify bindings stick inside an instance of a protocol")

  (defn make-a []
    (binding [*foo* 2]
      (->AA)))

  (t (make-a))

  (:foo (assoc (make-a) :foo 1)))
