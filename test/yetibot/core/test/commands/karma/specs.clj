(ns yetibot.core.test.commands.karma.specs
  (:require
   [midje.sweet :refer [fact => =not=> truthy]]
   [yetibot.core.commands.karma.specs :as karma.spec]
   [clj-time.core :as time]
   [clj-time.coerce :as time.coerce]
   [clojure.spec.alpha :as s]))

(def epoch (time.coerce/to-long (time/now)))
(def test-user (str "test-user-" epoch))
(def test-voter (str "test-voter-" epoch))
(def test-note (str "test-note-" epoch))

;; The context passed to our command handlers
(def ctx {:user {:id test-voter :name test-voter}})

(fact ctx
      (let [invalid-ctx (assoc-in ctx [:user :name] nil)]
        (s/valid? ::karma.spec/ctx ctx)             => truthy
        (s/valid? ::karma.spec/ctx invalid-ctx) =not=> truthy))

(fact user-id
      (s/valid? ::karma.spec/user-id "lake")       => truthy
      (s/valid? ::karma.spec/user-id "7lake")      => truthy
      (s/valid? ::karma.spec/user-id "lake7")      => truthy
      (s/valid? ::karma.spec/user-id "la-ke")      => truthy
      (s/valid? ::karma.spec/user-id "--lake") =not=> truthy
      (s/valid? ::karma.spec/user-id "lake--") =not=> truthy)

(fact action
      (first (s/conform ::karma.spec/action "++")) => :positive
      (first (s/conform ::karma.spec/action "--")) => :negative
      (s/valid? ::karma.spec/action "+-")      =not=> truthy
      (s/valid? ::karma.spec/action "")        =not=> truthy)

(fact note
      (s/valid? ::karma.spec/note "Lake") => truthy
      (s/valid? ::karma.spec/note "42")   => truthy
      (s/valid? ::karma.spec/note 42) =not=> truthy)
