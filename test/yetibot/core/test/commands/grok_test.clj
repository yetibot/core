(ns yetibot.core.test.commands.grok-test
  (:require [midje.sweet :refer [facts fact => contains provided]]
            [yetibot.core.commands.grok :as g]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.chat :as chat]
            [yetibot.core.commands.agent :as agent]
            [discljord.messaging :as discord]))

(facts "about grok-cmd"
       (fact "it returns an error if xAI is not configured"
             (g/grok-cmd {:match "hello" :chat-source {}}) => (contains {:result/error string?})
             (provided (xai/configured?) => false))

       (fact "it generates text and returns response with model footer when configured"
             (g/grok-cmd {:match "what is 2+2" :chat-source {}})
             => (contains {:result/value "4\n\nSent via grok-4.7 | Cost: $0.0001"
                           :result/data {:prompt "what is 2+2" :response "4"}})
             (provided
               (xai/configured?) => true
               (xai/generate-text "what is 2+2") => {:text "4" :cost 0.00012}
               (g/discord?) => false))

       (fact "on Discord, it starts a thread, sends message to the thread channel, and suppresses response"
             (meta (g/grok-cmd {:match "what is 2+2" :chat-source {:raw-event {:channel-id "chan-1" :id "msg-1"}}}))
             => (contains {:suppress true})
             (provided
               (xai/configured?) => true
               (xai/generate-text "what is 2+2") => {:text "4" :cost 0.00012}
               (g/discord?) => true
               (g/rest-conn) => "mock-rest-conn"
               (g/start-thread! "chan-1" "msg-1" "what is 2+2") => "thread-1"
               (discord/create-message! "mock-rest-conn" "thread-1" :content "*thinking* ▰") => (atom {:id "thinking-msg-id"})
               (discord/delete-message! "mock-rest-conn" "thread-1" "thinking-msg-id") => (atom {})
               (chat/chat-data-structure "4\n\nSent via grok-4.7 | Cost: $0.0001") => nil))

       (fact "on Discord inside a thread, it retrieves and includes thread history"
             (meta (g/grok-cmd {:match "what is the next number?"
                                :chat-source {:raw-event {:channel-id "thread-1" :id "msg-2"}}}))
             => (contains {:suppress true})
             (provided
               (xai/configured?) => true
               (g/discord?) => true
               (g/rest-conn) => "mock-rest-conn"
               (discord/get-channel! "mock-rest-conn" "thread-1") => (atom {:type 11 :name "grok thread"})
               (discord/get-channel-messages! "mock-rest-conn" "thread-1" :limit 20) => (atom [{:id "msg-1"
                                                                                                :content "grok the numbers 1, 2, 3"
                                                                                                :timestamp 1000
                                                                                                :author {:username "Alice" :bot false}}
                                                                                               {:id "msg-2"
                                                                                                :content "grok what is the next number?"
                                                                                                :timestamp 2000
                                                                                                :author {:username "Alice" :bot false}}])
               (xai/generate-text [{:role "user" :content "the numbers 1, 2, 3"}
                                   {:role "user" :content "what is the next number?"}])
               => {:text "4" :cost 0.00012}
               (g/start-thread! "thread-1" "msg-2" "what is the next number?") => "thread-1"
               (discord/create-message! "mock-rest-conn" "thread-1" :content "*thinking* ▰") => (atom {:id "thinking-msg-id"})
               (discord/delete-message! "mock-rest-conn" "thread-1" "thinking-msg-id") => (atom {})
               (chat/chat-data-structure "4\n\nSent via grok-4.7 | Cost: $0.0001") => nil)))

(facts "about grok-agent-cmd"
       (fact "it returns an error if agent is not configured"
             (g/grok-agent-cmd {:match ["grok agent test" "test"] :chat-source {}}) => (contains {:result/error string?})
             (provided (agent/configured?) => false))

       (fact "it delegates to agent-cmd when agent is configured"
             (g/grok-agent-cmd {:match ["grok agent test" "test"] :chat-source {}}) => "agent-result"
             (provided
               (agent/configured?) => true
               (agent/agent-cmd (contains {:match ["test" "test"]})) => "agent-result")))

(facts "about format-grok-response"
       (fact "returns text untouched if reasoning is blank"
             (#'g/format-grok-response "hello" "") => "hello"
             (#'g/format-grok-response "hello" nil) => "hello")

       (fact "adds blockquote formatted reasoning if present"
             (#'g/format-grok-response "hello" "first step\nsecond step")
             => "### 🧠 Thinking Process\n> first step\n> second step\n\nhello"))
