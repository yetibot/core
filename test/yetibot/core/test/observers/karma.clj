(ns yetibot.core.test.observers.karma
  (:require
   [midje.sweet :refer [namespace-state-changes with-state-changes fact => truthy]]
   [yetibot.core.observers.karma :refer [pos-reaction neg-reaction reaction-hook message-hook]]
   [yetibot.core.models.karma :as model ]
   [yetibot.core.commands.karma :refer [pos-emoji neg-emoji error]]
   [yetibot.core.db :as db]
   [clj-time.core :as time]
   [clj-time.coerce :as time.coerce]))

(def epoch (time.coerce/to-long (time/now)))
(def test-user (str "test-user-" epoch))
(def test-voter (str "test-voter-" epoch))
(def test-note (str "test-note-" epoch))

;; Mock reaction event passed to our observers
(def reaction-event {:user {:id test-voter :name test-voter}
                     :message-user {:id test-user}})

(def pos-reaction-event (assoc reaction-event :reaction pos-reaction))
(def neg-reaction-event (assoc reaction-event :reaction neg-reaction))

;; Mock message event passed to our observers
(def message-event {:user {:id test-voter :name test-voter}})

(defn mk-message-event
  [emoji, user]
  (assoc message-event :body (format "%s @%s" emoji user)))

(def pos-message-event (mk-message-event pos-emoji test-user))
(def neg-message-event (mk-message-event neg-emoji test-user))
(def pos-message-self-event (mk-message-event pos-emoji test-voter))
(def neg-message-self-event (mk-message-event neg-emoji test-voter))

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
        (let [e (assoc-in neg-reaction-event [:message-user :id] test-voter)]
          (reaction-hook e) => dec-success?))

  (fact "reaction-hook precludes a users from incrementing their own karma"
        (let [e (assoc-in pos-reaction-event [:message-user :id] test-voter)]
          (reaction-hook e) => (-> error :karma :result/error)))

  ;; Message Observer
  (fact "message-hook can increment karma for another user"
        (message-hook pos-message-event) => inc-success?)

  (fact "message-hook can decrement karma for another user"
        (message-hook neg-message-event) => dec-success?)

  (fact "message-hook allows a user to decrement their own karma"
        (message-hook neg-message-self-event) => dec-success?)

  (fact "message-hook precludes a users from incrementing their own karma"
        (message-hook pos-message-self-event) => (-> error :karma :result/error)))
