(ns yetibot.core.test.commands.bameme-test
  (:require [midje.sweet :refer [facts fact => contains provided anything]]
            [yetibot.core.commands.bameme :as bm]
            [yetibot.core.util.gemini :as gemini]))

(facts "about bameme-cmd"
       (fact "it generates a meme and returns URL with model and cost footer"
             (bm/bameme-cmd {:match "bameme drake: writing tests / shipping to prod" :chat-source {}})
             => (contains {:result/value #"http://localhost:3003/generated-images/img123.png\n\nSent via gemini-3.6-flash-image-preview \| Cost: \$0.039"
                           :result/data {:id "img123" :prompt "bameme drake: writing tests / shipping to prod" :url "http://localhost:3003/generated-images/img123.png"}})
             (provided
               (gemini/configured?) => true
               (yetibot.core.util.image-input/extract-images "bameme drake: writing tests / shipping to prod" {}) => {:prompt "drake: writing tests / shipping to prod" :image-urls []}
               (gemini/generate-image "Create a meme in the style of the 'drake' meme template with this text: writing tests / shipping to prod" anything []) => {:data "bytes" :mime-type "image/png"}
               (yetibot.core.webapp.routes.images/store-image! {:data "bytes" :mime-type "image/png"}) => "img123"
               (gemini/yetibot-base-url) => "http://localhost:3003"
               (gemini/gemini-model) => "gemini-3.6-flash-image-preview"
               (gemini/cost-per-image) => 0.039)))
