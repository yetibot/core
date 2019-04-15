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

(defn mk-message-event
  "Return mock message event to pass to our observers"
  [emoji, user]
  (let [e {:user {:id test-voter :name test-voter}}]
    (assoc e :body (format "%s @%s" emoji user))))

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
    (let [e (mk-message-event pos-emoji test-user)]
      (message-hook e)) => inc-success?)

  (fact "message-hook can decrement karma for another user"
    (let [e (mk-message-event neg-emoji test-user)]
      (message-hook e))=> dec-success?)

  (fact "message-hook allows a user to decrement their own karma"
    (let [e (mk-message-event neg-emoji test-voter)]
      (message-hook e)) => dec-success?)

  (fact "message-hook precludes a users from incrementing their own karma"
    (let [e (mk-message-event pos-emoji test-voter)]
      (message-hook e)) => (-> error :karma :result/error)))
