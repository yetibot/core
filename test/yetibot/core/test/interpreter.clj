(ns yetibot.core.test.interpreter
  (:require
   [yetibot.core.interpreter :as i]
   [yetibot.core.models.default-command :refer [fallback-enabled?
                                                configured-default-command]]
   [yetibot.core.parser]
   [midje.sweet :refer [=> provided contains fact facts every-checker anything]]))

(facts
 "about handle-cmd"
 (fact
  "handles legit echo command and returns command args"
  (i/handle-cmd "echo hello world" {}) => "hello world")
 (fact
  "handles non-legit 'somerandom' command and tries to use 'help' when
   fallback is enabled and does not have defined fallback command"
  (i/handle-cmd "somerandom command" {}) => (contains "help somerandom")
  ;; forcing config'ed default command to be "help" to avoid any potential
  ;;   testing issues due to custom configs
  (provided (configured-default-command) => "help"))
 (fact
  "handles non-legit 'somerandom' command and suppresses it when fallback
   is disabled"
  (i/handle-cmd "somerandom command" {}) => {}
  (provided (fallback-enabled?) => false)))

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
