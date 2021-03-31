(ns yetibot.core.test.models.default-command
  (:require [yetibot.core.models.default-command :as dc]
            [yetibot.core.config :refer [get-config]]
            [midje.sweet :refer [=> provided anything fact facts throws]]))

(facts
 "about configured-default-command"
 (fact
  "returns a custom default command"
  (dc/configured-default-command) => "findme"
  (provided (get-config anything [:default :command]) => {:value "findme"}))
 (fact
  "returns the help command if no default command is set"
  (dc/configured-default-command) => "help"
  (provided (get-config anything [:default :command]) => {:error :not-found})))

(facts
 "about fallback-help-text-override"
 (fact
  "returns a custom help text message"
  (dc/fallback-help-text-override) => "findme"
  (provided (get-config anything [:command :fallback :help :text]) => {:value "findme"}))
 (fact
  "returns nil when no custom text message is found"
  (dc/fallback-help-text-override) => nil
  (provided (get-config anything [:command :fallback :help :text]) => {:error :not-found})))

(facts
 "about fallback-enabled?"
 (fact
  "returns true when 'true' is passed"
  (dc/fallback-enabled?) => true
  (provided (get-config anything [:command :fallback :enabled]) => {:value "true"}))
 (fact
  "returns true when any string (not= 'false') is passed"
  (dc/fallback-enabled?) => true
  (provided (get-config anything [:command :fallback :enabled]) => {:value "somerandomstring"}))
 (fact
  "returns true when nil is passed"
  (dc/fallback-enabled?) => true
  (provided (get-config anything [:command :fallback :enabled]) => {:value nil}))
 (fact
  "returns true when blank is passed"
  (dc/fallback-enabled?) => true
  (provided (get-config anything [:command :fallback :enabled]) => {:value "   "}))
 (fact
  "returns true when <Boolean>false is passed"
  (dc/fallback-enabled?) => true
  (provided (get-config anything [:command :fallback :enabled]) => {:value false}))
 (fact
  "returns true when {:error anything} is passed"
  (dc/fallback-enabled?) => true
  (provided (get-config anything [:command :fallback :enabled]) => {:error :not-found}))
 (fact
  "throws exception when <Boolean> true is passed"
  (dc/fallback-enabled?) => (throws Exception)
  (provided (get-config anything [:command :fallback :enabled]) => {:value true}))
 (fact
  "returns false when 'false' is passed"
  (dc/fallback-enabled?) => false
  (provided (get-config anything [:command :fallback :enabled]) => {:value "false"})))
