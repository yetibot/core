(ns yetibot.core.test.commands.alias
  (:require [midje.sweet :refer [facts fact => every-checker against-background
                                 provided contains truthy falsey]]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.db.alias :as model]
            [yetibot.core.hooks :refer [cmd-unhook]]
            [yetibot.core.models.help :as help]
            [midje.util :refer [testable-privates]]))

(facts
 "about add-alias"
 (let [cmd-name "hello"
       alias-info {:cmd-name cmd-name
                   :cmd "echo hello"
                   :user-id 123}
       alias-id {:id 123}]
   (fact
    "will update an alias, with its ID, when the existing alias cmd name
     already exists"
    (alias/add-alias alias-info) => alias-info
    (provided (#'alias/existing-alias cmd-name) => alias-id
              (model/update-where alias-id alias-info) => nil))
   (fact
    "will create a new alias when the cmd name doesn't already exist"
    (alias/add-alias alias-info) => alias-info
    (provided (#'alias/existing-alias cmd-name) => nil
              (model/create alias-info) => nil))))

(facts
 "about list-aliases"
 (let [aliases [{:cmd-name "hello" :cmd "echo hello"}]]
   (fact
    "when aliases exist, returns result data and values, no errors"
    (:result/data (alias/list-aliases :ignored)) => aliases
    (provided (model/find-all) => aliases))
   (fact
    "when no aliases exist, returns result error with string"
    (:result/error (alias/list-aliases :ignored)) => string?
    (provided (model/find-all) => []))))

(facts
 "about remove-alias"
 (let [cmd "hello"
       id 123
       alias {:match [:random cmd]}]
   (fact
    "when an alias matches, returns result error with string"
    (:result/data (alias/remove-alias alias))
    => (contains {:id id :cmd cmd})
    (provided (#'alias/existing-alias cmd) => {:id 123}
              (model/delete id) => :diddelete
              (cmd-unhook cmd) => :didunhook))
   (fact
    "when no aliases matches, returns result error with string"
    (:result/error (alias/remove-alias alias))
    => (every-checker string?
                      (contains cmd))
    (provided (#'alias/existing-alias cmd) => nil))))

(testable-privates yetibot.core.commands.alias cleaned-cmd-name)
(facts
 "about cleaned-cmd-name"
 (fact
  "returns 'cleaned' 1st element of command string args"
  (cleaned-cmd-name "  hello world  ") => "hello"))

(testable-privates yetibot.core.commands.alias built-in?)
(facts
 "about built-in? (all builtin commands w/ docs - all alias commands)"
 (against-background (help/get-docs) => {"echo" {}}
                     (model/find-all) => [{:cmd-name "alias1"}])
 (fact
  "returns the name of the command if considered a built-in"
  (built-in? "echo") => truthy)
 (fact
  "returns nil if NOT considered a built-in"
  (built-in? "alias1") => falsey))
