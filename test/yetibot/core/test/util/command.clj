(ns yetibot.core.test.util.command
  (:require
   yetibot.core.commands.echo
   [midje.sweet :refer [fact facts => against-background]]
   [yetibot.core.util.command :refer [whitelist blacklist
                                      embedded-cmds command-enabled?
                                      extract-command]]))

(facts "About embedded commands"
       (fact "Embedded commands that aren't actually known commands are not parsed"
             (embedded-cmds "`these` are the `invalid embedded commands`") => empty?)
       (fact
        "Known embedded commands are properly extracted"
        (embedded-cmds "`echo your temp:` wonder what the `temp 98101` is")
        => [[:expr
             [:cmd
              [:words "echo" [:space " "] "your" [:space " "] "temp:"]]]]))

(facts "About whitelists and blacklists"
       (against-background
        [(whitelist) => #{#"list"} (blacklist) => #{}]
        (fact "echo is not whitelisted"
              (command-enabled? "echo") => false)
        (fact "list is whitelisted"
              (command-enabled? "list") => true)
        (fact "help and alias ignore whitelist"
              (command-enabled? "help") => true
              (command-enabled? "category") => true))

       (against-background
        [(whitelist) => #{} (blacklist) => #{#"help" #"list"}]
        (fact "echo is not blacklisted"
              (command-enabled? "echo") => true)
        (fact "list is blacklisted"
              (command-enabled? "list") => false)
        (fact "help ignores blacklist"
              (command-enabled? "help") => true))

       (against-background
        [(whitelist) => #{} (blacklist) => #{}]
        (fact "echo is enabled by default"
              (command-enabled? "echo") => true)
        (fact "list is enabled by default"
              (command-enabled? "list") => true)))

(facts "about extract-command"
       (fact "Extracting commands allows specifying a prefix"
             (let [prefix "?"
                   body (str prefix "command arg1 arg2")]
               (extract-command body prefix) => [body (subs body 1)]))

       (fact "Nothing is extracted from a potential command if the prefix does not match"
             (let [prefix "?"
                   body "|command arg1 arg2"]
               (extract-command body prefix) => nil?)))
