(ns yetibot.core.test.commands.help
  (:require [midje.sweet :refer [facts fact => contains provided]]
            [yetibot.core.commands.help :as h]
            [yetibot.core.models.default-command :refer [fallback-enabled?
                                                         fallback-help-text-override]]
            [yetibot.core.models.help :refer [get-alias-docs get-docs get-docs-for
                                              fuzzy-get-docs-for]]))

(facts
 "about fallback-help-text"
 (fact
  "it will, by default, return text explaining how fallback commands are enabled
   and that the defalt command is `help`"
  (h/fallback-help-text) => (contains "default command is `help`"))
 
 (fact
  "it will tell you that fallback commands are disabled"
  (h/fallback-help-text) => (contains "commands are disabled")
  (provided (fallback-enabled?) => false))
 
 (fact
  "it will return whatever the fallback-help-text-override is if it returns a
   truthy value"
  (h/fallback-help-text) => :returnme
  (provided (fallback-help-text-override) => :returnme)))

(facts
 "about alias-help-text"
 (fact
  "it will get the keys of available aliases, sort them, and put into fancy
   output tell you which aliases are available"
  (h/alias-help-text) => #"(?is)aliases.*`one`.*`two`"
  (provided (get-alias-docs) => {"two" 2
                                 "one" 1}))
 
 (fact
  "it will return nil when there are no available aliases"
  (h/alias-help-text) => nil
  (provided (get-alias-docs) => {})))

(facts
 "about help-topics"
 (fact
  "it will tell you all about how to use help, how to use category, the status
   of the fallback command and what it is, as well as show you a sorted list
   of available commands, which we manually seeded via (get-docs)"
  (h/help-topics nil)
  => #"(?is)`help <command>`.*`category`.*default command is `help`.*available commands.*`one`.*`two`"
  (provided (get-docs) => {"two" 2
                           "one" 1})))

(facts
 "about help-for-topic"
 (fact
  "it will return a seq'ed collection of command docstrings that have the prefix
   match of `help`"
  (h/help-for-topic {:args "help"}) => (seq (get-docs-for "help")))

 (fact
  "it will do a fuzzy lookup for commands that kinda-sorta match the
   provided prefix (`hellp` in this case), and return a seq'ed collection of
   command docstrings that matched"
  (h/help-for-topic {:args "hellp"}) => (seq (fuzzy-get-docs-for "hellp")))

 (fact
  "it will return an error result that lets you know that it couldn't find any
   commands that match the provided command prefix"
  (:result/error (h/help-for-topic {:args "iwillfailbadly"}))
  => #"any help for topic.*iwillfailbadly"))

(facts
 "about help-all-cmd"
 (fact
  "it will get all the known command docstrings, format them into newlined
   output and return a string'ed result"
  (h/help-all-cmd nil) => #"(?is)help all.*help for <topic>"))
