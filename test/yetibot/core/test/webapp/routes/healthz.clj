(ns yetibot.core.test.webapp.routes.healthz
  (:require [yetibot.core.webapp.routes.healthz :refer [fully-operational?]]
            [yetibot.core.db :as db]
            [midje.sweet :refer [=> fact facts with-state-changes
                                 before after]]))

(facts
 "about fully-operational?"
 (with-state-changes [(before :facts
                              (reset! db/connected? true))
                      (after :facts
                              (reset! db/connected? false))]
   (fact
    "will show as operational even when no adapters are active"
    ;; i get that we are referring to a protocol, and it appears each adapter
    ;; defines it's own behavior .. but yikes !! i have no clue
    (fully-operational?) => true)))
