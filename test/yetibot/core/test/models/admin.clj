(ns yetibot.core.test.models.admin
  (:require [yetibot.core.models.admin :as a]
            [midje.sweet :refer [=> provided fact facts]]))

(facts
 "about admin-only-command?"
 (let [adm-cmds {:value {:commands ["yes"]}}]
   (fact
    "returns true for admin only command"
    (a/admin-only-command? "yes") => true
    (provided (a/config) => adm-cmds))
   (fact
    "returns false for non-admin only command"
    (a/admin-only-command? "no") => false
    (provided (a/config) => adm-cmds)))
 (fact
  "returns false when admin commands not found"
  (a/admin-only-command? "no") => false
  (provided (a/config) => {:error :not-found})))


(facts
 "about user-is-admin?"
 (let [adm-users {:value {:users ["yes"]}}]
   (fact
    "returns true if user is in admin user list"
    (a/user-is-admin? {:id "yes"}) => true
    (provided (a/config) => adm-users))
   (fact
    "returns false if user is NOT in admin user list"
    (a/user-is-admin? {:id "no"}) => false
    (provided (a/config) => adm-users)))
 (fact
  "returns false when admin users not found"
  (a/user-is-admin? {:id "no"}) => false
  (provided (a/config) => {:error :not-found})))
