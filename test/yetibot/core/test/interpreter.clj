(ns yetibot.core.test.interpreter
  (:require
   [yetibot.core.interpreter :as i]
   [yetibot.core.models.default-command :refer [fallback-enabled?]]
   [yetibot.core.parser]
   [midje.sweet :refer [=> provided contains fact facts every-checker anything]]))

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
