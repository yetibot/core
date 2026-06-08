(ns yetibot.core.test.commands.veo-test
  (:require [midje.sweet :refer [facts fact => contains provided]]
            [yetibot.core.commands.veo :as v]
            [yetibot.core.util.gemini :as gemini]))

(facts "about veo-budget-cmd"
       (fact "it returns an error if Gemini is not configured"
             (v/veo-budget-cmd {}) => (contains {:result/error string?})
             (provided (gemini/configured?) => false))

       (fact "it returns budget status if configured"
             (v/veo-budget-cmd {}) => (contains {:result/value string? :result/data map?})
             (provided
               (gemini/configured?) => true
               (gemini/budget-status) => {:images-generated 0
                                          :max-images 100
                                          :spent 0.0
                                          :budget 10.0
                                          :remaining 10.0
                                          :images-left 100
                                          :veo-clips-left 20
                                          :veo-cost-units 5
                                          :agent-sessions-left 10
                                          :agent-cost-units 10
                                          :month "2026-05"})))
