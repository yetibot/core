(ns yetibot.core.test.commands.grok-test
  (:require [midje.sweet :refer [facts fact => contains provided]]
            [yetibot.core.commands.grok :as g]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.util.gemini :as gemini]))

(facts "about grok-cmd"
       (fact "it returns an error if xAI is not configured"
             (g/grok-cmd {:match "grok space kitty" :chat-source {}}) => (contains {:result/error string?})
             (provided (xai/configured?) => false))

       (fact "it generates an image and returns URL with model footer when configured"
             (g/grok-cmd {:match "grok space kitty" :chat-source {}})
             => (contains {:result/value #"http://localhost:3003/generated-images/grok123.png\n\nSent via grok-imagine-image-2.0"
                           :result/data {:id "grok123" :prompt "grok space kitty" :url "http://localhost:3003/generated-images/grok123.png"}})
             (provided
               (xai/configured?) => true
               (yetibot.core.util.image-input/extract-images "grok space kitty" {}) => {:prompt "space kitty" :image-urls []}
               (xai/generate-image "space kitty") => {:data "grokbytes" :mime-type "image/jpeg"}
               (yetibot.core.webapp.routes.images/store-image! {:data "grokbytes" :mime-type "image/jpeg"}) => "grok123"
               (gemini/yetibot-base-url) => "http://localhost:3003")))
