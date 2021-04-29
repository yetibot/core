(ns yetibot.core.test.commands.alias
  (:require [midje.sweet :refer [facts fact =>
                                 provided]]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.db.alias :as model]))

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
