(ns yetibot.core.test.util.gemini-test
  (:require
   [midje.sweet :refer [=> fact facts contains provided anything roughly]]
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

(facts "about model pricing"
       (fact "it maps pro models to pro pricing"
             (gemini/model-pricing "gemini-3.6-pro") => {:input 2.00 :cached 0.50 :output 12.00}
             (gemini/model-pricing "gemini-3.6-pro-preview-customtools") => {:input 2.00 :cached 0.50 :output 12.00})
       (fact "it maps flash-lite models to lite pricing"
             (gemini/model-pricing "gemini-2.5-flash-lite") => {:input 0.10 :cached 0.025 :output 0.40})
       (fact "it maps flash models to flash pricing"
             (gemini/model-pricing "gemini-3.6-flash") => {:input 0.075 :cached 0.01875 :output 0.30})
       (fact "it defaults to pro pricing"
             (gemini/model-pricing "unknown-model") => {:input 2.00 :cached 0.50 :output 12.00}))

(facts "about stats cost calculation"
       (fact "it calculates correct cost based on tokens and model type"
             (gemini/calculate-stats-cost
              {:models {:gemini-3.6-pro-preview {:tokens {:input 100000 :candidates 10000 :cached 0}}}})
             => (roughly 0.32)
             (gemini/calculate-stats-cost
              {:models {:gemini-2.5-flash-lite {:tokens {:input 50000 :candidates 2000 :cached 10000}}}})
             => (roughly 0.00605)
             (gemini/calculate-stats-cost nil) => 0.0))

(facts "about cost-units calculation"
       (fact "it maps dollar cost to image-equivalent units"
             (gemini/calculate-cost-units 0.10) => integer?))
