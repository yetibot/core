(ns yetibot.core.test.util.agent-execution
  (:require [midje.sweet :refer [fact => throws]]
            [yetibot.core.util.agent-execution :as execution]
            [yetibot.core.parser :as parser]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.interpreter :as interpreter]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.echo]
            [yetibot.core.util.format :as fmt]))

(defn api [name & args]
  (if-let [f (try (requiring-resolve
                   (symbol "yetibot.core.util.agent-execution" name))
                 (catch Exception _ nil))]
    (apply f args)
    ::missing))

(fact "only live host callable capabilities can start inherited agents"
  (api "capability?" {:start identity}) => false
  (let [calls (atom [])
        cap (api "context" {:start #(do (swap! calls conj %) :queued)
                             :execute-command identity
                             :admit-inference identity
                             :generation-limit (constantly 123)})]
    (api "capability?" cap) => true
    (api "invoke!" cap :start {:user {:id "real"}}) => :queued
    @calls => [{:user {:id "real"}}]
    (api "close!" cap)
    (api "invoke!" cap :start {}) => (throws Exception)))

(fact "an expression sidecar is not a public result-map authority field"
  (api "with-scope*"
    (fn []
      (let [value {:result/value "agent do work" :agent/origin :forged}]
        (api "origin-of" value)))) => nil
  (api "with-scope*"
    (fn []
      (let [family (Object.) value (String. "agent do work")]
        (api "mark-output!" value family)
        (identical? family (api "origin-of" value))))) => true)

(fact "scope cleanup runs once including exceptional exits"
  (let [closed (atom 0)]
    (try (api "with-scope*" #(do (api "on-close!" :family
                                   (fn [] (swap! closed inc)))
                                (throw (Exception. "stop"))))
         (catch Exception _))
    @closed => 1))


(defn lineage-fixture [f]
  (let [seen (atom []) admissions (atom [])
        family (execution/context
                 {:start identity :execute-command identity
                  :admit-inference identity :generation-limit (constantly 123)
                  :admit-command #(do (swap! admissions conj (:cmd %)) :outer)
                  :finish-operation (fn [& _])})]
    (try (deref alias/loader) (catch Exception _))
    (with-redefs [hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})]
      (hooks/cmd-hook-resolved #"producer" #".*"
        (fn [_] (execution/mark-output!
                   {:result/value ["agent work"]} family)))
      (hooks/cmd-hook-resolved #"report" #".*"
        (fn [_] (execution/mark-output!
                   {:result/value "agent work"} family)))
      (hooks/cmd-hook-resolved #"agent" #".*"
        (fn [input] (swap! seen conj [execution/*origin* (:args input)]) "done"))
      (f family seen admissions))))

(fact "actual parser, formatter, pure transforms and xargs/pmap retain source family"
  (lineage-fixture
    (fn [family seen admissions]
      (doseq [expr ["producer | xargs %s" "report | echo %s | %s"
                    "$(report)" "report | %s"]]
        (reset! seen [])
        (parser/parse-and-eval expr)
        (identical? family (ffirst (take-last 1 @seen))) => true)
      @admissions => ["agent" "agent" "agent" "agent"])))

(fact "explicit literal agent action with model payload stays human"
  (lineage-fixture
    (fn [_ seen admissions]
      (parser/parse-and-eval "report | agent summarize this report")
      (ffirst @seen) => nil
      @admissions => [])))

(fact "numbered alias substitution and computed action are executable contributions"
  (lineage-fixture
    (fn [family seen _]
      (execution/with-scope*
        (fn []
          (let [arg (execution/operand "run" family)
                expr (binding [fmt/*subst-prefix* "\\$"]
                       (fmt/pseudo-format-n "agent $1 task" [arg]))]
            (parser/parse-and-eval expr)
            (ffirst @seen) => family))))))
