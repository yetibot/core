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
