(ns yetibot.core.test.commands.grok-test
  (:require [midje.sweet :refer [facts fact => contains provided throws anything]]
            [yetibot.core.commands.grok :as g]
            [yetibot.core.util.xai :as xai]
            [discljord.messaging :as discord]
            [yetibot.core.chat :as chat]))

(facts "about grok-cmd"
       (fact "it returns an error if xAI is not configured"
             (g/grok-cmd {:match "hello" :chat-source {}}) => (contains {:result/error string?})
             (provided (xai/configured?) => false))

       (fact "it generates text and returns response with model footer when configured"
             (g/grok-cmd {:match "what is 2+2" :chat-source {}})
             => (contains {:result/value "4\n\nSent via grok-4.6 | Cost: $0.0001"
                           :result/data {:prompt "what is 2+2" :response "4"}})
             (provided
               (xai/configured?) => true
               (xai/generate-text "what is 2+2") => {:text "4" :cost 0.00012}
               (g/discord?) => false))

       (fact "on Discord, it starts a thread, sends message to the thread channel, streams response, and suppresses response"
             (meta (g/grok-cmd {:match "what is 2+2" :chat-source {:raw-event {:channel-id "chan-1" :id "msg-1"}}}))
             => (contains {:suppress true})
             (provided
               (xai/configured?) => true
               (g/discord?) => true
               (g/start-thread! "chan-1" "msg-1" "what is 2+2") => "thread-1"
               (g/rest-conn) => "mock-rest-conn"
               (discord/create-message! "mock-rest-conn" "thread-1" :content "*Thinking...*") => (future {:id "thinking-msg-1"})
               (xai/generate-text-stream "what is 2+2" anything) => nil))

       (fact "on Discord, if text generation fails, it sends the error to the thread channel and suppresses response"
             (meta (g/grok-cmd {:match "what is 2+2" :chat-source {:raw-event {:channel-id "chan-1" :id "msg-1"}}}))
             => (contains {:suppress true})
             (provided
               (xai/configured?) => true
               (g/discord?) => true
               (g/start-thread! "chan-1" "msg-1" "what is 2+2") => "thread-1"
               (g/rest-conn) => "mock-rest-conn"
               (discord/create-message! "mock-rest-conn" "thread-1" :content "*Thinking...*") => (future {:id "thinking-msg-1"})
               (xai/generate-text-stream "what is 2+2" anything) => (throw (Exception. "API Error"))
               (discord/edit-message! "mock-rest-conn" "thread-1" "thinking-msg-1" :content "Text generation failed: API Error") => (future {}))))
