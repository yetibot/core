(ns yetibot.core.test.models.admin
  (:require [yetibot.core.models.admin :as a]
            [midje.sweet :refer [=> fact facts]]))

(facts
 "about admin-only-command?"
 (let [adm-cmds {:value {:commands ["yes"]}}]
   (fact
    "returns true for admin only command"
    (a/admin-only-command? "yes" adm-cmds) => true)
   (fact
    "returns false for non-admin only command"
    (a/admin-only-command? "no" adm-cmds) => false)))

(facts
 "about user-is-admin?"
 (let [adm-users {:value {:users ["yes"]}}]
   (fact
    "returns true if user is in admin user list"
    (a/user-is-admin? {:id "yes"} adm-users) => true)
   (fact
    "returns false if user is NOT in admin user list"
    (a/user-is-admin? {:id "no"} adm-users) => false)))
