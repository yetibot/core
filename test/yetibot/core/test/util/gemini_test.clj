(ns yetibot.core.test.util.gemini-test
  (:require
   [midje.sweet :refer [=> fact facts contains provided anything]]
   [yetibot.core.db.image-budget :as image-budget]
   [yetibot.core.util.gemini :as gemini]))

(facts "about budget-status"
       (fact "it calculates budget status including veo and agent details"
             (gemini/budget-status) => (contains {:images-generated integer?
                                                  :max-images integer?
                                                  :spent number?
                                                  :budget number?
                                                  :remaining number?
                                                  :images-left integer?
                                                  :veo-clips-left integer?
                                                  :veo-cost-units integer?
                                                  :agent-sessions-left integer?
                                                  :agent-cost-units integer?
                                                  :month string?})
             (provided
               (image-budget/query anything) => [])))
