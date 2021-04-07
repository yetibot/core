(ns yetibot.core.test.adapters.slack
  (:require [yetibot.core.adapters :as adapters]
            [yetibot.core.adapters.adapter :as a]
            [yetibot.core.adapters.slack :as slack]
            [yetibot.core.chat :as chat]
            [midje.sweet :refer [=> fact facts contains provided
                                 anything]]))

(facts
 "about slack-config"
 (fact
  "gets default endpoint if none given"
  (slack/slack-config {}) => (contains {:api-url "https://slack.com/api"}))
 (fact
  "uses custom endpoint if given"
  (slack/slack-config {:endpoint "iamcustom"}) => (contains {:api-url "iamcustom"}))
 (fact
  "returns nil token if none given"
  (slack/slack-config {}) => (contains {:token nil}))
 (fact
  "returns custom token if given"
  (slack/slack-config {:token "iamcustom"}) => (contains {:token "iamcustom"})))

;; referencing slack data here :: https://api.slack.com/methods/channels.list
(facts
 "about channels-in"
 (fact
  "returns only channels YB is in"
  (slack/channels-in anything) => '({:is_member true :name "yes"})
  (provided (slack/list-channels anything) => {:channels [{:is_member true
                                                  :name "yes"}
                                                 {:is_member false
                                                  :name "no"}]})))

(facts
 "about chan-or-group-name"
 (fact
  "handles a channel"
  (slack/chan-or-group-name {:is_channel true :name "channel"})
  => "#channel")
 (fact
  "handles a group"
  (slack/chan-or-group-name {:is_channel false :name "group"})
  => "group"))

(facts
 "about unencode-message"
 (fact
  "unencodes a URL"
  (slack/unencode-message "<https://imgflip.com>") => "https://imgflip.com")
 (fact
  "unencodes a URL with text after it"
  (slack/unencode-message "<https://imgflip.com> .base-img[src!=''] src") =>
  "https://imgflip.com .base-img[src!=''] src")
 (fact
  "unencodes a URL with surrounding text"
  (slack/unencode-message
   "Why does slack surround URLs with cruft? Jerk. <https://imgflip.com> .base-img[src!=''] src")
  => "Why does slack surround URLs with cruft? Jerk. https://imgflip.com .base-img[src!=''] src")
 (fact
  "unencodes multiple URLs"
  (slack/unencode-message "Foo <https://imgflip.com> bar <https://www.google.com>") =>
  "Foo https://imgflip.com bar https://www.google.com")
 (fact
  "unencodes Slack's weird @channel and @here encodings"
  (slack/unencode-message "<!here> Slaaaaaaaaaaaaaaack") => "@here Slaaaaaaaaaaaaaaack"
  (slack/unencode-message "<!channel> also") => "@channel also"))



;; functions to test some comment code
(defn slack-configs []
  (filter
   (fn [c] (= "slack" (:type c)))
   (vals (adapters/adapters-config))))

(def config (slack/slack-config (last (slack-configs))))

(comment
  ;; replace these with real IDs to try it out
  (slack/entity-with-name-by-id
   config {:channel "C11111114"})
  (slack/entity-with-name-by-id
   config {:channel "G11111111"})
  (slack/entity-with-name-by-id
   config
   {:type "message"
    :channel "D11111111"
    :user "U11111111"
    :text "!echo hi"})

  ;; test sending a message and getting the history of
  (let [adapter (first (a/active-adapters))]
    (slack/history adapter "G1QD1DNG2")

    (binding [chat/*target* "D0HFDJHA4"]
      (a/send-msg adapter "hi"))

    (slack/react adapter "balloon" "D0HFDJHA4"))

  ;; test bindings when calling an adapter function
  (binding [*config* (last (slack-configs))]
    (slack/list-channels))
  
  (binding [*config* (last (slack-configs))]
    (slack/channels-cached))

  (binding [*config* (last (slack-configs))]
    (slack/channels))

  (binding [*config* (last (slack-configs))]
    (:groups (slack/list-groups))))
