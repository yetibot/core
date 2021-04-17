(ns yetibot.core.test.adapters
  (:require [yetibot.core.adapters :as a]
            [yetibot.core.adapters.adapter :as aa]
            [midje.sweet :refer [=> fact facts provided anything
                                 every-checker contains just throws]]
            [yetibot.core.config :refer [get-config]]))

(facts
 "about adapters-config"
 (fact
  "merges adapter from config"
  (a/adapters-config) => (every-checker map?
                                        not-empty
                                        (contains a/web-adapter-config)
                                        (contains {:example {:type "slack"
                                                             :token "abc123"}}))
  (provided (get-config anything [:adapters]) =>
            {:value {:example {:type "slack"
                               :token "abc123"}}}))
 (fact
  "returns only web adapter config if no legit adapter is present in confif"
  (a/adapters-config) => (every-checker map?
                                        not-empty
                                        (just a/web-adapter-config))
  (provided (get-config anything [:adapters]) =>
            {:error :not-found})))

(facts
 "about make-adapter"
 (fact
  "makes slack adapter"
  (instance? yetibot.core.adapters.slack.Slack
             (a/make-adapter {:type "slack"})) => true)
 (fact
  "makes IRC adapter"
  (instance? yetibot.core.adapters.irc.IRC
             (a/make-adapter {:type "irc"})) => true)
 (fact
  "makes web adapter"
  (instance? yetibot.core.adapters.web.Web
             (a/make-adapter {:type "web"})) => true)
 (fact
  "makes mattermost adapter"
  (instance? yetibot.core.adapters.mattermost.Mattermost
             (a/make-adapter {:type "mattermost"})) => true)
 (fact
  "throws exception for unknown adapter"
  (a/make-adapter {:type "throwme"}) => (throws Exception)))

(facts
 "about stop"
 (fact
  "stops and drops all active adapters"
  ;; setup test adapter
  (aa/register-adapter! "some-uuid" {:config {:type "web"}})
  ;; do the thing and verify no more active adapters
  (a/stop) => {}
  (provided (run! anything anything) => {})
  ;; double check what YB thinks is active after a stop
  (aa/active-adapters) => nil))
