(ns yetibot.core.test.models.default-command
  (:require [yetibot.core.models.default-command :as dc]
            [midje.sweet :refer [=> fact facts]]))

(facts
 "about configured-default-command"
 (fact
  "returns a custom default command"
  (dc/configured-default-command {:value "findme"}) => "findme")
 (fact
  "returns the help command if no default command is set"
  (dc/configured-default-command {:error :not-found}) => "help"))

(facts
 "about fallback-help-text-override"
 (fact
  "returns a custom help text message"
  (dc/fallback-help-text-override {:value "findme"}) => "findme")
 (fact
  "returns nil when no custom text message is found"
  (dc/fallback-help-text-override {:error :not-found}) => nil))

(facts
 "about fallback-enabled?"
 (fact
  "returns true when 'true' is passed"
  (dc/fallback-enabled? {:value "true"}) => true)
 (fact
  "returns true when 'blank' is passed"
  (dc/fallback-enabled? {:value "   "}) => true)
 (fact
  "returns true when some non string-ified boolen is passed"
  (dc/fallback-enabled? {:value "thiswillalsobetrue"}) => true)
 (fact
  "returns true when an error is returned"
  (dc/fallback-enabled? {:error :not-found}) => true)
 (fact
  "returns false when 'false' is passed"
  (dc/fallback-enabled? {:value "false"}) => false
  ;; this use to return true since comparison was between "false" and false
  ;; mods stringify :value so comparison will now be be between "false" and "false"
  (dc/fallback-enabled? {:value false}) => false))
