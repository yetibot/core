(ns yetibot.core.test.commands.image-test
  (:require [midje.sweet :refer [facts fact => contains provided]]
            [yetibot.core.commands.image :as img]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.util.gemini :as gemini]))

(facts "about image-cmd"
       (fact "it returns an error if xAI is not configured"
             (img/image-cmd {:match "image space kitty" :chat-source {}}) => (contains {:result/error string?})
             (provided (xai/configured?) => false))

       (fact "it generates an image and returns URL with model footer when configured"
             (img/image-cmd {:match "image space kitty" :chat-source {}})
             => (contains {:result/value #"http://localhost:3003/generated-images/grok123.png\n\nSent via grok-imagine-image-2.0 \| Cost: \$0.040"
                           :result/data {:id "grok123" :prompt "image space kitty" :url "http://localhost:3003/generated-images/grok123.png"}})
             (provided
               (xai/configured?) => true
               (xai/cost-per-image) => 0.04
               (yetibot.core.util.image-input/extract-images "image space kitty" {}) => {:prompt "space kitty" :image-urls []}
               (xai/generate-image "space kitty") => {:data "grokbytes" :mime-type "image/jpeg"}
               (yetibot.core.webapp.routes.images/store-image! {:data "grokbytes" :mime-type "image/jpeg"}) => "grok123"
               (gemini/yetibot-base-url) => "http://localhost:3003")))
