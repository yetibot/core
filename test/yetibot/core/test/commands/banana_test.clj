(ns yetibot.core.test.commands.banana-test
  (:require [midje.sweet :refer [facts fact => contains provided]]
            [yetibot.core.commands.banana :as b]
            [yetibot.core.util.gemini :as gemini]))

(facts "about banana-budget-cmd"
       (fact "it returns an error if Gemini is not configured"
             (b/banana-budget-cmd {}) => (contains {:result/error string?})
             (provided (gemini/configured?) => false))

       (fact "it returns budget status if configured"
             (b/banana-budget-cmd {}) => (contains {:result/value string? :result/data map?})
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

(facts "about banana-cmd"
       (fact "it generates an image and returns URL with model and cost footer"
             (b/banana-cmd {:match "banana monkey" :chat-source {}})
             => (contains {:result/value #"http://localhost:3003/generated-images/img123.png\n\nSent via gemini-3.1-flash-image-preview \| Cost: \$0.039"
                           :result/data {:id "img123" :prompt "banana monkey" :url "http://localhost:3003/generated-images/img123.png"}})
             (provided
               (gemini/configured?) => true
               (yetibot.core.util.image-input/extract-images "banana monkey" {}) => {:prompt "monkey" :image-urls []}
               (gemini/generate-image "Generate an image: monkey" b/banana-system-instruction []) => {:data "bytes" :mime-type "image/png"}
               (yetibot.core.webapp.routes.images/store-image! {:data "bytes" :mime-type "image/png"}) => "img123"
               (gemini/yetibot-base-url) => "http://localhost:3003"
               (gemini/gemini-model) => "gemini-3.1-flash-image-preview"
               (gemini/cost-per-image) => 0.039)))
