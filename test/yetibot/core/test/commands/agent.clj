(ns yetibot.core.test.commands.agent
  (:require
   [midje.sweet :refer [fact facts => anything]]
   [midje.checkers :refer [contains]]
   [clojure.string :as string]
   [yetibot.core.util.ai-gateway :as ai-gateway]
   [yetibot.core.commands.agent :as agent]))

(facts "about redact"
  (fact "strips an embedded token from a url"
    (agent/redact "clone https://x-access-token:supersecret@github.com/yetibot/core.git")
    => "clone https://x-access-token:***@github.com/yetibot/core.git")
  (fact "strips a bare gh token"
    (agent/redact "export GH_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789")
    => (contains "***"))
  (fact "tolerates nil"
    (agent/redact nil) => nil))

(facts "about pr-urls"
  (fact "extracts and dedupes pull request urls"
    (agent/pr-urls "opened https://github.com/yetibot/core/pull/5 — see https://github.com/yetibot/core/pull/5")
    => ["https://github.com/yetibot/core/pull/5"])
  (fact "finds multiple"
    (agent/pr-urls "https://github.com/a/b/pull/1 https://github.com/c/d/pull/2")
    => ["https://github.com/a/b/pull/1" "https://github.com/c/d/pull/2"])
  (fact "empty when none"
    (agent/pr-urls "no prs here") => []))

(facts "about build-system-prompt"
  (fact "gives the bot an identity and persona"
    (agent/build-system-prompt) => (contains "Yetibot")
    (agent/build-system-prompt) => (contains "Bonzi"))
  (fact "is clear it can't take actions like opening PRs"
    (agent/build-system-prompt) => (contains "cannot")
    (agent/build-system-prompt) => (contains "pull requests"))
  (fact "asks for the final answer only (no narration)"
    (agent/build-system-prompt) => (contains "final answer"))
  (fact "tells the agent to mention people with their <@id> token"
    (agent/build-system-prompt) => (contains "<@id>")))

(facts "about build-user-message"
  (fact "includes the request"
    (agent/build-user-message "do x" nil nil) => (contains "do x"))
  (fact "includes conversation context when present"
    (agent/build-user-message "do x" "alice: hi" nil) => (contains "alice: hi"))
  (fact "omits the context section when blank"
    (agent/build-user-message "do x" "" nil) => #(not (string/includes? % "REFERENCE ONLY")))
  (fact "includes the mention glossary when present"
    (agent/build-user-message "do x" nil "• <@1> is Bob") => (contains "<@1> is Bob")))

(facts "about final messages"
  (fact "say-working is a generic status, not the prompt"
    (agent/say-working) => (contains "Bonzi"))
  (fact "say-final shows the agent's answer"
    (agent/say-final "Added the bagif command" nil) => (contains "Added the bagif command"))
  (fact "say-final appends any cited PR links"
    (agent/say-final "done" ["https://github.com/yetibot/core/pull/242"]) => (contains "pull/242"))
  (fact "say-final copes with a blank answer"
    (agent/say-final "" nil) => (contains "done"))
  (fact "say-timeout names the limit"
    (agent/say-timeout 5) => (contains "5 min")))

(facts "about agent config defaults"
  (fact "default timeout is 15 minutes"
    (agent/agent-timeout-ms) => 900000)
  (fact "default model is a fast Kimi model"
    (agent/model) => "kimi-k2.5"))

(facts "about configured?"
  (fact "is available only when the AI gateway is configured"
    (agent/configured?) => true
    (provided (ai-gateway/configured?) => true))
  (fact "is unavailable when the gateway is not configured"
    (agent/configured?) => false
    (provided (ai-gateway/configured?) => false)))

(facts "about mention-glossary"
  (fact "prefers the server nickname and keeps the <@id> token"
    (agent/mention-glossary [{:id "123" :username "alice" :global-name "Alice A"
                              :member {:nick "WandPotato"}}])
    => (contains "<@123> is WandPotato"))
  (fact "falls back to global-name then username when there's no nick"
    (agent/mention-glossary [{:id "99" :username "bob"}]) => (contains "<@99> is bob"))
  (fact "is empty when there are no mentions"
    (agent/mention-glossary nil) => ""))

(facts "about agent-cmd guards"
  (fact "replies in persona when nothing is configured"
    (agent/agent-cmd {:match ["agent do x" "do x"] :chat-source {}})
    => (contains "banana")
    (provided (agent/configured?) => false)))

;; --- restart resilience: resume runs a restart left in-flight ---

(facts "about resume config defaults"
  (fact "default max attempts is 2 — the original plus one retry"
    (agent/agent-max-attempts) => 2))

(facts "about resume-action"
  (fact "resumes a fresh interrupted run"
    (agent/resume-action 1 1000 2 100000) => :resume)
  (fact "gives up once attempts reach the cap"
    (agent/resume-action 2 1000 2 100000) => :give-up)
  (fact "treats a run older than the cutoff as stale"
    (agent/resume-action 1 200000 2 100000) => :stale)
  (fact "the attempts cap wins over staleness"
    (agent/resume-action 2 200000 2 100000) => :give-up))

(facts "about resume-request"
  (fact "notes the previous attempt was interrupted"
    (agent/resume-request "add a bagif command") => (contains "interrupted"))
  (fact "keeps the original request text"
    (agent/resume-request "add a bagif command") => (contains "add a bagif command")))

(facts "about thread-context"
  (fact "returns empty string if the channel is not a thread (type is not 10, 11, or 12)"
    (#'agent/thread-context "channel-id-123") => ""
    (provided
      (#'agent/rest-conn) => "mock-conn"
      (discljord.messaging/get-channel! "mock-conn" "channel-id-123") => (atom {:type 0 :name "general"})))

  (fact "returns thread context if the channel is a thread (type 11) with multiple messages"
    (#'agent/thread-context "thread-id-456") => "[thread topic] cool-thread\nalice: hello\nbob: world"
    (provided
      (#'agent/rest-conn) => "mock-conn"
      (discljord.messaging/get-channel! "mock-conn" "thread-id-456") => (atom {:type 11 :name "cool-thread"})
      (#'agent/all-channel-messages "thread-id-456") => [{:author {:username "alice"} :content "hello" :timestamp 1}
                                                         {:author {:username "bob"} :content "world" :timestamp 2}]))

  (fact "returns empty string if the channel is a thread but has 1 or fewer messages (first message/prompt only)"
    (#'agent/thread-context "thread-id-789") => ""
    (provided
      (#'agent/rest-conn) => "mock-conn"
      (discljord.messaging/get-channel! "mock-conn" "thread-id-789") => (atom {:type 11 :name "cool-thread"})
      (#'agent/all-channel-messages "thread-id-789") => [{:author {:username "alice"} :content "hello" :timestamp 1}])))

(facts "about agent subcommands"
  (fact "agent-list-commands-cmd returns available commands in JSON"
    (agent/agent-list-commands-cmd {}) => {:result/value "{\"commands\":[\"cmd1\",\"cmd2\"]}"}
    (provided
      (yetibot.core.models.help/get-docs) => {"cmd1" "doc1" "cmd2" "doc2"}))

  (fact "agent-list-aliases-cmd returns available aliases in JSON"
    (agent/agent-list-aliases-cmd {}) => {:result/value "{\"aliases\":[{\"cmd-name\":\"hello\",\"cmd\":\"echo hello\"}]}"}
    (provided
      (yetibot.core.db.alias/find-all) => [{:cmd "echo hello" :cmd-name "hello"}]))

  (fact "agent-run-cmd evaluates a yetibot command"
    (agent/agent-run-cmd {:match ["agent run echo hello" "echo hello"]}) => {:result/value "hello"}
    (provided
      (yetibot.core.handler/record-and-run-raw "echo hello" anything nil anything) => [{:result "hello"}])))
