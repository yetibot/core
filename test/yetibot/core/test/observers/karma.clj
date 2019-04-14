(ns yetibot.core.test.observers.karma
  (:require
   [midje.sweet :refer [namespace-state-changes with-state-changes fact => truthy]]
   [yetibot.core.observers.karma :refer [pos-reaction neg-reaction reaction-hook message-hook]]
   [yetibot.core.models.karma :as model ]
   [yetibot.core.commands.karma :refer [pos-emoji neg-emoji]]
   [yetibot.core.db :as db]
   [clj-time.core :as time]
   [clj-time.coerce :as time.coerce]))

(def epoch (time.coerce/to-long (time/now)))
(def test-user (str "test-user-" epoch))
(def test-voter (str "test-voter-" epoch))
(def test-note (str "test-note-" epoch))
;;(def test-score 1000000)
;;(def _) "entire-match not used by test"

;; The reaction event passed to our observers
(def reaction-event {:user {:id test-voter :name test-voter}
                     :message-user {:id test-user}})

(def pos-reaction-event (assoc reaction-event :reaction pos-reaction))

(def neg-reaction-event (assoc reaction-event :reaction neg-reaction))

(defn- reply-is?
  [emoji s]
  (let [re (re-pattern (format "^%s <@.+>" emoji))]
    (string? (re-find re s))))

(def inc-success? (partial reply-is? pos-emoji))

(def dec-success? (partial reply-is? neg-emoji))


(namespace-state-changes (before :contents (db/start)))

(with-state-changes [(after :facts (model/delete-user! test-user))]

  ;; Reaction Observer
  
  (fact "reaction-hook can increment karma for another user"
    (reaction-hook pos-reaction-event) => inc-success?)

  (fact "reaction-hook can decrement karma for another user"
    (reaction-hook neg-reaction-event) => dec-success?)

  (fact "reaction-hook allows a user to decrement their own karma"
    (let [e (assoc-in neg-reaction-event [:message-user :id] test-voter )]
      (reaction-hook e)) => dec-success?)

  (fact "reaction-hook precludes a users from incrementing their own karma")

  ;; Message Observer

  (fact "message-hook can increment karma for another user")

  (fact "message-hook can increment karma for another user and include a note")

  (fact "message-hook can decrement karma for another user")

  (fact "message-hook can decrement karma for another user and include a note")

  (fact "message-hook allows a user to decrement their own karma")

  (fact "message-hook precludes a users from incrementing their own karma")

  )
