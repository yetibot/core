(ns yetibot.core.test.commands.grok-test
  (:require [midje.sweet :refer [facts fact => contains provided]]
            [yetibot.core.commands.grok :as g]
            [yetibot.core.util.xai :as xai]))

(facts "about grok-cmd"
       (fact "it returns an error if xAI is not configured"
             (g/grok-cmd {:match "hello" :chat-source {}}) => (contains {:result/error string?})
             (provided (xai/configured?) => false))

       (fact "it generates text and returns response with model footer when configured"
             (g/grok-cmd {:match "what is 2+2" :chat-source {}})
             => (contains {:result/value "4\n\nSent via grok-4.6"
                           :result/data {:prompt "what is 2+2" :response "4"}})
             (provided
               (xai/configured?) => true
               (xai/generate-text "what is 2+2") => "4")))
