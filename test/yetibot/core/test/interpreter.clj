(ns yetibot.core.test.interpreter
  (:require
   [yetibot.core.interpreter :as i]
   [yetibot.core.models.default-command :refer [fallback-enabled?
                                                configured-default-command]]
   [yetibot.core.commands.help]
   [yetibot.core.commands.echo]
   [yetibot.core.parser]
   [midje.sweet :refer [=> provided contains against-background
                        fact facts every-checker anything]]))

(facts
 "about handle-cmd"
 (fact
  "handles legit echo command and returns command args"
  (i/handle-cmd "echo hello world" {}) => "hello world")
 (fact
  "handles un-handleable non-legit 'somerandom' command with fallback? true"
  (i/handle-cmd "somerandom command" {:fallback? true})
  => (contains "how to handle somerandom command"))
 (fact
  "handles non-legit 'somerandom' command and suppresses it when fallback
   is disabled"
  (i/handle-cmd "somerandom command" {}) => {}
  (provided (fallback-enabled?) => false))
 (fact
  "non-legit command is picked up by the help command when it is loaded and
   returns some doc-strings for the help command."
  (require 'yetibot.core.commands.help :reload)
  ;; forcing the help command to be the config'ed def cmd
  (against-background (configured-default-command) => "help")
  (let [cmd-result (i/handle-cmd "somerandom command" {})]
    cmd-result => coll?
    cmd-result => (contains
                   (:doc (meta #'yetibot.core.commands.help/help-all-cmd)))
    cmd-result => (contains
                   (:doc (meta #'yetibot.core.commands.help/help-for-topic)))))
 (fact
  "legit echo command is issued incorrectly (echop); handle-cmd sees there is a legit
   similar command and throws it into the help command and gets back echo doc-string"
  (require 'yetibot.core.commands.help :reload)
  (against-background (configured-default-command) => "help")
  (let [cmd-result (i/handle-cmd "echop" {})]
    cmd-result => coll?
    cmd-result => (contains
                   (:doc (meta #'yetibot.core.commands.echo/echo-cmd))))))

(facts
 "about handle-expr"
 (fact
  "returns non-empty map for single command and contains expected keys/value"
  (let [he (i/handle-expr
            #'yetibot.core.parser/transformer
            '([:cmd [:words "echo" [:space " "] "hello"]]))]
    he => (every-checker map? not-empty)
    he => (contains {:value "hello"})
    he => (contains {:settings anything})
    he => (contains {:data anything})))
 (fact
  "returns non-empty map for multiple commands and contains expected keys/value"
  (let [he (i/handle-expr
            #'yetibot.core.parser/transformer
            '([:cmd [:words "echo" [:space " "] "foo"]]
              [:cmd [:words "echo" [:space " "] "bar"]]))]
    he => (every-checker map? not-empty)
    he => (contains {:value "bar foo"})
    he => (contains {:settings anything})
    he => (contains {:data anything}))))
