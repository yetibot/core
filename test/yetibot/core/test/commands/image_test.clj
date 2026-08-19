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
             => (contains {:result/value #"http://localhost:3003/generated-images/grok123.png\n\nSent via grok-imagine-image-2.0 \| Cost: \$0.04"
                           :result/data {:id "grok123" :prompt "image space kitty" :url "http://localhost:3003/generated-images/grok123.png"}})
             (provided
               (xai/configured?) => true
               (yetibot.core.util.image-input/extract-images "image space kitty" {}) => {:prompt "space kitty" :image-urls []}
               (xai/generate-image "space kitty" []) => {:data "grokbytes" :mime-type "image/jpeg"}
               (yetibot.core.webapp.routes.images/store-image! {:data "grokbytes" :mime-type "image/jpeg"}) => "grok123"
               (gemini/yetibot-base-url) => "http://localhost:3003"))

       (fact "it generates an edited image when image-urls/attachments are provided"
             (img/image-cmd {:match "sketch this" :chat-source {:raw-event {:attachments [{:url "https://example.com/source.jpg" :content-type "image/jpeg"}]}}})
             => (contains {:result/value #"http://localhost:3003/generated-images/grok123.png\n\nSent via grok-imagine-image-2.0 \| Cost: \$0.04"
                           :result/data {:id "grok123" :prompt "sketch this" :url "http://localhost:3003/generated-images/grok123.png"}})
             (provided
               (xai/configured?) => true
               (yetibot.core.util.image-input/extract-images "sketch this" (contains {:raw-event map?})) => {:prompt "sketch this" :image-urls ["https://example.com/source.jpg"]}
               (xai/generate-image "sketch this" ["https://example.com/source.jpg"]) => {:data "grokbytes" :mime-type "image/jpeg"}
               (yetibot.core.webapp.routes.images/store-image! {:data "grokbytes" :mime-type "image/jpeg"}) => "grok123"
               (gemini/yetibot-base-url) => "http://localhost:3003"))

       (fact "it generates an edited image with multiple images using grok when Gemini is not configured"
             (img/image-cmd {:match "sketch these" :chat-source {:raw-event {:attachments [{:url "https://example.com/source1.jpg" :content-type "image/jpeg"}
                                                                                          {:url "https://example.com/source2.jpg" :content-type "image/jpeg"}]}}})
             => (contains {:result/value #"http://localhost:3003/generated-images/grok123.png\n\nSent via grok-imagine-image-2.0 \| Cost: \$0.04"
                           :result/data {:id "grok123" :prompt "sketch these" :url "http://localhost:3003/generated-images/grok123.png"}})
             (provided
               (xai/configured?) => true
               (gemini/configured?) => false
               (yetibot.core.util.image-input/extract-images "sketch these" (contains {:raw-event map?})) => {:prompt "sketch these" :image-urls ["https://example.com/source1.jpg" "https://example.com/source2.jpg"]}
               (xai/generate-image "sketch these" ["https://example.com/source1.jpg" "https://example.com/source2.jpg"]) => {:data "grokbytes" :mime-type "image/jpeg"}
               (yetibot.core.webapp.routes.images/store-image! {:data "grokbytes" :mime-type "image/jpeg"}) => "grok123"
               (gemini/yetibot-base-url) => "http://localhost:3003"))

       (fact "it generates an edited image combining multiple images using Gemini when multiple image-urls/attachments are provided and Gemini is configured"
             (img/image-cmd {:match "sketch these" :chat-source {:raw-event {:attachments [{:url "https://example.com/source1.jpg" :content-type "image/jpeg"}
                                                                                          {:url "https://example.com/source2.jpg" :content-type "image/jpeg"}]}}})
             => (contains {:result/value #"http://localhost:3003/generated-images/gemini123.png\n\nSent via gemini-3.1-flash-image-preview \| Cost: \$0.039"
                           :result/data {:id "gemini123" :prompt "sketch these" :url "http://localhost:3003/generated-images/gemini123.png"}})
             (provided
               (xai/configured?) => true
               (gemini/configured?) => true
               (yetibot.core.util.image-input/extract-images "sketch these" (contains {:raw-event map?})) => {:prompt "sketch these" :image-urls ["https://example.com/source1.jpg" "https://example.com/source2.jpg"]}
               (gemini/generate-image "Combine these images: sketch these" nil ["https://example.com/source1.jpg" "https://example.com/source2.jpg"]) => {:data "geminibytes" :mime-type "image/png"}
               (yetibot.core.webapp.routes.images/store-image! {:data "geminibytes" :mime-type "image/png"}) => "gemini123"
               (gemini/yetibot-base-url) => "http://localhost:3003"
               (gemini/gemini-model) => "gemini-3.1-flash-image-preview"
               (gemini/cost-per-image) => 0.039)))
