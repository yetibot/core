(ns yetibot.core.test.util.agent-execution
  (:require [midje.sweet :refer [fact => throws]]
            [yetibot.core.util.agent-execution :as execution]
            [yetibot.core.parser :as parser]
            [yetibot.core.handler :as handler]
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
      (hooks/cmd-hook-resolved #"reportstring" #".*"
        (fn [_] (execution/mark-output! (String. "agent work") family)))
      (hooks/cmd-hook-resolved #"reporterror" #".*"
        (fn [_] (execution/mark-output!
                   {:result/error "agent work"} family)))
      (hooks/cmd-hook-resolved #"agent" #".*"
        (fn [input] (swap! seen conj [execution/*origin* (:args input)]) "done"))
      (binding [interpreter/*current-user* nil
                interpreter/*yetibot-user* nil
                interpreter/*chat-source* {:room "fixture"}]
        (f family seen admissions)))))

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

(fact "actual alias reparsing conveys admission owner without a second charge"
  (lineage-fixture
    (fn [family seen admissions]
      (#'alias/wire-alias {:cmd-name "boundedalias" :cmd "agent work"})
      (hooks/cmd-hook-resolved #"aliassource" #".*"
        (fn [_] (execution/mark-output! {:result/value "boundedalias"} family)))
      (parser/parse-and-eval "aliassource | %s")
      (ffirst @seen) => family
      @admissions => ["boundedalias"]
      (reset! seen [])
      (reset! admissions [])
      (parser/parse-and-eval "boundedalias")
      (ffirst @seen) => nil
      @admissions => []
      (#'alias/wire-alias {:cmd-name "expandedbody" :cmd "$s"})
      (parser/parse-and-eval "report | expandedbody")
      (ffirst (take-last 1 @seen)) => family
      @admissions => ["agent"])))

(fact "nil admissions cannot silently grant permission to dispatch"
  (let [cap (execution/context {:start identity :execute-command identity
                                :admit-inference (constantly nil)
                                :admit-command (constantly nil)
                                :generation-limit (constantly 123)})
        calls (atom 0)]
    (binding [execution/*origin* cap]
      (try (execution/with-command* "write" {:cmd "write"} #(swap! calls inc))
           (catch Exception _ :denied)) => :denied
      @calls => 0)))

(fact "timeout closure prevents later derived effects even if an earlier callback completed"
  (let [effects (atom 0)
        cap (execution/context {:start identity :execute-command identity
                                :admit-inference identity
                                :admit-command (constantly :own)
                                :finish-operation (fn [& _])
                                :generation-limit (constantly 123)})]
    (execution/with-scope*
      (fn []
        (execution/on-close! :cap #(execution/close! cap))
        (execution/close-scope! execution/*scope*)
        (binding [execution/*value-origin* cap]
          (try (execution/with-command* "agent literal" {:cmd "agent"}
                 #(swap! effects inc))
               (catch Exception _ :denied)) => :denied)
        @effects => 0))))

(fact "one failing scope closer cannot leave another source family open"
  (let [closed (atom 0)]
    (try
      (execution/with-scope*
        #(do (execution/on-close! :bad (fn [] (throw (Exception. "failed closer"))))
             (execution/on-close! :good (fn [] (swap! closed inc)))))
      (catch Exception _))
    @closed => 1))

(fact "the host sees the actual original AST alongside substituted control spans"
  (lineage-fixture
    (fn [_ _ _]
      (let [observed (atom nil)]
        (with-redefs-fn {(ns-resolve 'yetibot.core.util.agent-execution 'host)
                         (atom {:callbacks {:command-origin
                                  #(do (reset! observed %) nil)}})}
          #(parser/parse-and-eval "report | agent summarize this report"))
        (get-in @observed [:root-template :text])
        => "report | agent summarize this report"
        (first (get-in @observed [:command-template :ast])) => :cmd
        (get-in @observed [:command-template :text]) => "agent summarize this report"
        (count (:contributions @observed)) => 1))))

(fact "derived fallback routing cannot launder executable identity"
  (lineage-fixture
    (fn [family seen admissions]
      (hooks/cmd-hook-resolved #"unknownsource" #".*"
        (fn [_] (execution/mark-output! {:result/value "unknown-identity"} family)))
      (with-redefs [yetibot.core.models.default-command/fallback-enabled? (constantly true)
                    yetibot.core.models.default-command/configured-default-command
                    (constantly "agent")]
        (parser/parse-and-eval "unknownsource | %s"))
      (ffirst @seen) => family
      @admissions => ["agent"])))

(fact "late duplicate closer registration remains once-only"
  (let [calls (atom 0)]
    (execution/with-scope*
      #(do (execution/close-scope! execution/*scope*)
           (execution/on-close! :same (fn [] (swap! calls inc)))
           (execution/on-close! :same (fn [] (swap! calls inc)))))
    @calls => 1))

(fact "installed host callbacks are explicit, reloadable and never infer human from nil"
  (with-redefs-fn {(ns-resolve 'yetibot.core.util.agent-execution 'host) (atom nil)}
    (fn []
      (let [resumed (atom [])
            callbacks {:classify-agent (fn [_ _] nil)
                       :resume #(swap! resumed conj (:run-id %))}]
        (execution/install-host! :test-host callbacks)
        (execution/install-host! :test-host callbacks)
        @resumed => []
        (execution/agent-route {:chat-source {:adapter :api}}) => (throws Exception)
        (execution/install-host! :another callbacks) => (throws Exception)
        (execution/resume-inherited!
          [{:run-id "legacy"} {:run-id "unknown" :execution-kind "unknown"}
           {:run-id "inherited" :execution-kind "app-inherited-v1"}])
        @resumed => ["inherited"]
        (execution/with-scheduler* :legacy-unclassified 1
          #(execution/agent-route {})) => :legacy-scheduled
        (execution/with-scheduler* :human-literal 2
          #(execution/agent-route {})) => :human-literal
        (binding [execution/*scheduler-origin* {:kind :human-literal :id 2}]
          (execution/agent-route {})) => (throws Exception)))))

(fact "legacy compatibility labels cannot serve as inherited capabilities"
  (binding [execution/*origin* :legacy]
    (execution/agent-route {}) => (throws Exception))
  (with-redefs-fn {(ns-resolve 'yetibot.core.util.agent-execution 'host)
                   (atom {:callbacks {:classify-agent (fn [& _] :legacy)}})}
    #(execution/agent-route {})) => (throws Exception))


(fact "the actual handler timeout closes the family before a late derived stage"
  (lineage-fixture
    (fn [family seen _]
      (let [entered (promise) release (promise) finished (promise)
            original execution/with-command*]
        (hooks/cmd-hook-resolved #"slowproducer" #".*"
          (fn [_]
            (execution/on-close! :family #(execution/close! family))
            (deliver entered true)
            @release
            (execution/mark-output! {:result/value "agent work"} family)))
        (with-redefs [handler/expr-eval-timeout-ms 100
                      execution/with-command*
                      (fn [text input f]
                        (try (original text input f)
                             (finally (when (= "agent" (:cmd input))
                                        (deliver finished true)))))]
          (try
            (let [result (handler/record-and-run-raw "!slowproducer | %s" nil nil
                           {:record-yetibot-response? false})]
              (deref entered 1000 false) => true
              (:timeout? (first result)) => true
              (deliver release true)
              (deref finished 1000 false) => true
              @seen => [])
            (finally (deliver release true))))))))

(fact "subexpression payload lineage survives real echo, words, grep, xargs and aliases"
  (lineage-fixture
    (fn [family seen admissions]
      (#'alias/wire-alias {:cmd-name "payloadalias" :cmd "echo $s"})
      (#'alias/wire-alias {:cmd-name "computedalias" :cmd "$s"})
      (doseq [expr ["echo $(report) | %s"
                    "echo $(reportstring) | %s"
                    "echo $(report) | echo | %s"
                    "echo $(report) | words | xargs %s"
                    "echo $(report) | words | grep agent | xargs %s"
                    "echo $(report) | payloadalias | %s"
                    "echo $(report) | computedalias"
                    "echo $(reporterror) | words | grep agent | xargs %s"]]
        (reset! seen []) (reset! admissions [])
        (parser/parse-and-eval expr)
        (ffirst @seen) => family
        @admissions => ["agent"]))))

(fact "the original raw handler preserves subexpression family and actual caller"
  (lineage-fixture
    (fn [family seen admissions]
      (let [inputs (atom []) user {:id "caller"} bot {:id "bot"}
            source {:room "fixture"}]
        (hooks/cmd-hook-resolved #"agent" #".*"
          (fn [input]
            (swap! inputs conj (select-keys input [:user :yetibot-user :chat-source]))
            (swap! seen conj [execution/*origin* (:args input)]) "done"))
        (with-redefs [handler/add-user-message-to-history (fn [& _])
                      handler/add-bot-response-to-history (fn [& _])]
          (:result (first (handler/record-and-run-raw
                            "!echo $(report) | %s" user bot
                            {:record-yetibot-response? false}))) => "done")
        @inputs => [{:user user :yetibot-user bot :chat-source source}]
        (ffirst @seen) => family
        @admissions => ["agent"]))))

(fact "closed subexpression families cannot reach later computed effects"
  (lineage-fixture
    (fn [family seen admissions]
      (execution/close! family)
      (parser/parse-and-eval "echo $(report) | %s") => (throws Exception)
      ;; xargs catches child exceptions by design, but must not run the effect.
      (parser/parse-and-eval "echo $(report) | words | grep agent | xargs %s")
      @seen => []
      @admissions => [])))

(fact "literal human agent receiving subexpression report payload stays standalone"
  (lineage-fixture
    (fn [_ seen admissions]
      (parser/parse-and-eval "agent summarize $(report)")
      @seen => [[nil "summarize agent work"]]
      @admissions => [])))

(fact "marked error and derived handler results retain family at their real boundaries"
  (lineage-fixture
    (fn [family _ admissions]
      (execution/with-scope*
        (fn []
          (let [result (parser/parse-and-eval "reporterror")]
            (execution/origin-of result) => family
            (:error result) => "💥 Error in `reporterror`: agent work 💥")
          (let [result (interpreter/handle-cmd
                         (execution/operand "agent work" family) {})]
            result => "done"
            (execution/origin-of result) => family)))
      @admissions => ["agent"])))

(fact "alias empty slots do not shift marked arguments into computed controls"
  (lineage-fixture
    (fn [family seen admissions]
      (#'alias/wire-alias {:cmd-name "slotcontrol" :cmd "agent $2"})
      (#'alias/wire-alias {:cmd-name "slotpayload" :cmd "echo $2"})
      (execution/with-scope*
        (fn []
          ;; The second slot is empty, not the marked third-slot action.
          (parser/parse-and-eval
            (execution/join-text ["slotcontrol one  "
                                  (execution/operand "run" family)]))
          @seen => [[nil ""]]
          @admissions => []
          (reset! seen [])
          ;; A leading empty slot still leaves the second slot's real offsets.
          (parser/parse-and-eval
            (execution/join-text ["slotcontrol  "
                                  (execution/operand "run" family)]))
          @seen => [[family "run"]]
          @admissions => ["agent"]
          (reset! seen []) (reset! admissions [])
          (parser/parse-and-eval
            (execution/join-text ["slotpayload  "
                                  (execution/operand "agent" family) " | %s"]))
          @seen => [[family ""]]
          @admissions => ["agent"])))))
