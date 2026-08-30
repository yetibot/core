(ns yetibot.core.test.commands.grokimage-test
  (:require [midje.sweet :refer [facts fact => contains provided]]
            [yetibot.core.commands.grokimage :as grokimg]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.util.gemini :as gemini]))

(facts "about grokimage-cmd"
       (fact "it returns an error if xAI is not configured"
             (grokimg/grokimage-cmd {:match "grokimage space kitty" :chat-source {}}) => (contains {:result/error string?})
             (provided (xai/configured?) => false))

       (fact "it generates an image and returns URL with model footer when configured, without any system instruction"
             (grokimg/grokimage-cmd {:match "grokimage space kitty" :chat-source {}})
             => (contains {:result/value #"http://localhost:3003/generated-images/grok123.png\n\nSent via grok-imagine-image-2.0 \| Cost: \$0.04"
                           :result/data {:id "grok123" :prompt "grokimage space kitty" :url "http://localhost:3003/generated-images/grok123.png"}})
             (provided
               (xai/configured?) => true
               (yetibot.core.util.image-input/extract-images "grokimage space kitty" {}) => {:prompt "space kitty" :image-urls []}
               (xai/generate-image "space kitty" nil []) => {:data "grokbytes" :mime-type "image/jpeg"}
               (yetibot.core.webapp.routes.images/store-image! {:data "grokbytes" :mime-type "image/jpeg"}) => "grok123"
               (gemini/yetibot-base-url) => "http://localhost:3003"))

       (fact "it generates an edited image when image-urls/attachments are provided, without any system instruction"
             (grokimg/grokimage-cmd {:match "sketch this" :chat-source {:raw-event {:attachments [{:url "https://example.com/source.jpg" :content-type "image/jpeg"}]}}})
             => (contains {:result/value #"http://localhost:3003/generated-images/grok123.png\n\nSent via grok-imagine-image-2.0 \| Cost: \$0.04"
                           :result/data {:id "grok123" :prompt "sketch this" :url "http://localhost:3003/generated-images/grok123.png"}})
             (provided
               (xai/configured?) => true
               (yetibot.core.util.image-input/extract-images "sketch this" (contains {:raw-event map?})) => {:prompt "sketch this" :image-urls ["https://example.com/source.jpg"]}
               (xai/generate-image "sketch this" nil ["https://example.com/source.jpg"]) => {:data "grokbytes" :mime-type "image/jpeg"}
               (yetibot.core.webapp.routes.images/store-image! {:data "grokbytes" :mime-type "image/jpeg"}) => "grok123"
               (gemini/yetibot-base-url) => "http://localhost:3003")))
