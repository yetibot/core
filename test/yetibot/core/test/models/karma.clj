(ns yetibot.core.test.models.karma
  (:require
   [midje.sweet :refer [namespace-state-changes with-state-changes fact => truthy]]
   [yetibot.core.models.karma :refer [get-score
                                      get-high-scores
                                      get-high-givers
                                      delete-user!
                                      add-score-delta!
                                      get-notes]]
   [yetibot.core.db :as db]
   [clj-time.core :as time]
   [clj-time.coerce :as time.coerce]))

(def epoch (time.coerce/to-long (time/now)))
(def test-user-0 (str "test-user-0-" epoch))
(def test-voter-0 (str "test-voter-0-" epoch))
(def test-note (str "test-note-" epoch))

(def test-user-1 (str "test-user-1-" epoch))
(def test-voter-1 (str "test-voter-1-" epoch))
(def test-chat-source {:uuid :test :room "test-channel"})

(def max-scores 100)


(namespace-state-changes (before :contents (db/start)))

(with-state-changes [(after :facts (do (delete-user! test-user-0)
                                       (delete-user! test-user-1)))]

  (fact "a non-existing user has a score of 0"
        (get-score test-chat-source test-user-0) => 0)

  (fact "a user's score can be incremented"
        (add-score-delta! test-chat-source test-user-0 test-voter-0 1 nil)
        (get-score test-chat-source test-user-0) => 1)

  (fact "a user's score can be decremented"
        (add-score-delta! test-chat-source test-user-0 test-voter-0 -1 nil)
        (get-score test-chat-source test-user-0) => -1)

  (fact "score updates save an optional note"
        (add-score-delta! test-chat-source test-user-0 test-voter-0 1 test-note)
        (-> (get-notes test-chat-source test-user-0) first :note) => test-note)

  (fact "score changes save voter attribution"
        (add-score-delta! test-chat-source test-user-0 test-voter-0 1 test-note)
        (-> (get-notes test-chat-source test-user-0) first :voter-id) => test-voter-0)

  (fact "created-at timestamp seems reasonable"
        (add-score-delta! test-chat-source test-user-0 test-voter-0 1 test-note)
        (let [created-at (-> (get-notes test-chat-source test-user-0) first :created-at time.coerce/to-long)
              now        (-> (time/now) time.coerce/to-long)]
          (-> (- now created-at) (< 60)) => truthy))

  (fact "get-high-scores returns at least one item"
        (add-score-delta! test-chat-source test-user-0 test-voter-0 1 nil)
        (-> (get-high-scores {:cnt max-scores}) count (>= 1)) => truthy)

  ;; Brittle, potential for false negative, as we (intentionally)
  ;; don't clear the DB.
  (fact "get-high-scores should not includes scores of 0 or less"
        (add-score-delta! test-chat-source test-user-0 test-voter-0 1 nil)
        (add-score-delta! test-chat-source test-user-0 test-voter-0 -1 nil)
        (->> (get-high-scores {:cnt max-scores})
             (filter #(= (:user-id %) test-user-0))
             count) => 0)

  (fact "get-high-scores respects limit"
        (add-score-delta! test-chat-source test-user-0 test-voter-0 1 nil)
        (add-score-delta! test-chat-source test-user-1 test-voter-0 1 nil)
        (-> (get-high-scores {:cnt 1}) count (= 1)) => truthy)

  (fact "get-high-givers respects limit"
        (add-score-delta! test-chat-source test-user-0 test-voter-0 1 nil)
        (add-score-delta! test-chat-source test-user-0 test-voter-1 1 nil)
        (-> (get-high-givers 1) count (= 1)) => truthy))
