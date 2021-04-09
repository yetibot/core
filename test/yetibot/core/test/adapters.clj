(ns yetibot.core.test.adapters
  (:require [yetibot.core.adapters :as a]
            [midje.sweet :refer [=> fact facts provided anything
                                 every-checker contains just]]
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
             (a/make-adapter {:type "slack"})) => true))
