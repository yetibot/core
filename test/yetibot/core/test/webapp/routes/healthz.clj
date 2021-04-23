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
    "will show as operational when DB is conneted and no adapters are active"
    (fully-operational?) => true)))
