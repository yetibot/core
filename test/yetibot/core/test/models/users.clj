(ns yetibot.core.test.models.users
  (:require [yetibot.core.models.users :as u]
            [midje.sweet :refer [=> before after with-state-changes
                                 fact facts]]))

(def chat-source {:adapter :test :room "test"})
(def user-id "0")
(def username "devth")

(facts
 "about models.user"
 (with-state-changes [(before :facts
                              (u/add-user chat-source
                                          (u/create-user username
                                                         {:id user-id})))
                      (after :facts
                             (reset! u/users {}))]

   (fact
    "it can add a user to a chat source and get same user"
    (count (u/get-users chat-source)) => 1
    (:username (u/get-user chat-source user-id)) => username)

   (fact
    "it can update a user attribute that doesn't already exist"
    (:age (u/get-user chat-source user-id)) => nil
    (u/update-user chat-source user-id {:age 42})
    (:age (u/get-user chat-source user-id)) => 42)

   (fact
    "it can add a chat source to an existing user"
    (u/add-chat-source-to-user (assoc chat-source :room "newroom")
                               user-id)
    (count (:channels (u/get-user chat-source user-id))) => 2)

   (fact
    "it will always return false when asked if this user is yetibot"
    (u/is-yetibot? :doesntmatter) => false)

   (fact
    "will let me know if a user is active or not .. honestly, seems like it
     will always be true -- not seeing where this would ever be updated to
     be false"
    (u/is-active? (u/get-user chat-source user-id)) => true)

   (fact
    "will return all active users"
    (count (u/get-active-users)) => 1)

   (fact
    "will get a user by its ID; if none found, returns nil"
    (:id (u/get-user-by-id user-id)) => user-id
    (:username (u/get-user-by-id user-id)) => username
    (u/get-user-by-id "123") => nil)

   (fact
    "will get all active humans, which means everyone who is in the @users
     atom, because the check (is-yetibot?) always returns false"
    (count (u/get-active-humans)) => 1)

   (fact
    "will return all users who are 'like' a username; if none found, returns
     nil"
    (:id (u/find-user-like chat-source "dev")) => user-id
    (:username (u/find-user-like chat-source "dev")) => username
    (u/find-user-like chat-source "frank") => nil)))
