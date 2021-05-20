(ns yetibot.core.test.commands.observe
  (:require [midje.sweet :refer [=> facts fact contains]]
            [yetibot.core.commands.observe :as obs]))

(facts
 "about commands.observe"
 (fact
  "returns observed options that have no errors, contains the specified arg,
   and recognizes the -u option with provided value"
  (let [{:keys [arguments errors options]} (obs/parse-observe-opts
                                            "-u lol x")]
    errors => empty?
    arguments => (contains "x")
    (:user-pattern options) => "lol"))
 (fact
  "it will return an error when providing invalid options, but still will parse
   whatever valid options it can find"
  (let [{:keys [arguments errors options]} (obs/parse-observe-opts
                                            "-u lol -ewat x")]
    errors => coll?
    (first errors) => (contains "Failed to validate")
    arguments => (contains "x")
    (:user-pattern options) => "lol"))
 (fact
  "it will return a formatted observer of any pattern, no cmd, and no
   event type when passed param with nothing"
  (let [format (obs/format-observer {})]
    format => "[any pattern]:  [event type: ] "))
 (fact
  "it will return a formatted observer with the pattern, cmd, and all
   related map keys, wrapped in a '[<key>: <value>]' syntax; there are
   some exceptions related to keys with '-' and (user-)id"
  (let [format (obs/format-observer {:pattern "mypattern"
                                     :cmd "mycmd"
                                     :event-type "myeventtype"
                                     :id "myid"
                                     :user-pattern "myuserpattern"
                                     :channel-pattern "mychannelpattern"
                                     :user-id "myuserid"})]
    format => (contains "mypattern: mycmd")
    format => (contains "[event type: myeventtype]")
    format => (contains "[id myid]")
    format => (contains "[user pattern: myuserpattern]")
    format => (contains "[channel pattern: mychannelpattern]")
    format => (contains "[created by myuserid]"))))
