(ns yetibot.core.test.util.ai-usage
  (:require [clojure.java.jdbc :as jdbc]
            [midje.sweet :refer [fact =>]]
            [yetibot.core.db :as db]
            [yetibot.core.db.ai-usage :as store]
            [yetibot.core.handler :as handler]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.ai-usage :as usage]))

(defn tokens [body]
  (if-let [f (try (requiring-resolve 'yetibot.core.util.ai-usage/tokens)
                 (catch java.io.FileNotFoundException _ nil))]
    (f body)
    ::unimplemented))

(fact "usage extracts token counters, never content or invented zeroes"
  (tokens {:usage {:prompt_tokens 12 :completion_tokens 5
                   :prompt_tokens_details {:cached_tokens 3}
                   :completion_tokens_details {:reasoning_tokens 2}
                   :PRIVATE "PRIVATE"}
           :choices [{:message {:content "PRIVATE"}}]})
  => {:input_tokens 12 :output_tokens 5 :cached_tokens 3 :reasoning_tokens 2}
  (tokens {:usage {:input_tokens 0 :output_tokens 0}})
  => {:input_tokens 0 :output_tokens 0}
  (tokens {}) => {}
  (tokens {:usage {:prompt_tokens -1 :completion_tokens "12"}}) => {}
  (tokens {:usage {:input_tokens 1.5 :output_tokens ##Inf}}) => {}
  (tokens {:usage {:input_tokens 1000000000001}}) => {})

(fact "Workers and Gemini counters retain their actual distinct units"
  (tokens {:result {:usage {:prompt_tokens 8 :completion_tokens 2
                            :neurons 1.25}}})
  => {:input_tokens 8 :output_tokens 2 :neurons 1.25}
  (tokens {:usageMetadata {:promptTokenCount 18 :candidatesTokenCount 7
                           :cachedContentTokenCount 4 :thoughtsTokenCount 3}})
  => {:input_tokens 18 :output_tokens 7 :cached_tokens 4 :reasoning_tokens 3})

(defn capture [attrs request project]
  (if-let [f (try (requiring-resolve 'yetibot.core.util.ai-usage/capture!)
                 (catch Exception _ nil))]
    (f attrs request project)
    ::unimplemented))

(fact "standalone utilities without a running database retain their API"
  (let [calls (atom 0)]
    (with-redefs [db/connected? (atom false)
                  jdbc/get-connection
                  (fn [& _] (throw (AssertionError. "No database IO")))]
      (capture {:provider "fixture" :model "one" :operation "text"}
               #(do (swap! calls inc) {:value "answer"}) (constantly {}))
      => {:value "answer"}
      @calls => 1)))

(fact "an unavailable initial durable write blocks inference without secrets"
  (let [calls (atom 0)
        result (with-redefs [db/connected? (atom true)
                            jdbc/get-connection
                            (fn [& _] (throw (Exception. "PRIVATE-DB-URL")))]
                 (try
                   (capture {:provider "fixture" :model "one"
                             :operation "text"}
                            #(swap! calls inc) (constantly {}))
                   (catch Exception e (.getMessage e))))]
    @calls => 0
    result => "AI usage tracking is unavailable. No AI request was sent."))

(fact "a silently rejected reservation cannot permit an unrecorded request"
  (let [calls (atom 0)]
    (with-redefs [db/connected? (atom true)
                  store/begin! (constantly [0])
                  store/finish! (constantly [0])]
      (try (capture {:provider "fixture" :model "one" :operation "text"}
                    #(swap! calls inc) (constantly {}))
           (catch Exception e (.getMessage e)))
      => "AI usage tracking is unavailable. No AI request was sent."
      @calls => 0)))

(fact "a started bot cannot silently disable its required ledger"
  (let [flag (resolve 'yetibot.core.util.ai-usage/required?)
        original (when flag @@flag)
        calls (atom 0)]
    (try
      (with-redefs [db/connected? (atom false)
                    store/start! (constantly [0])
                    store/begin! (fn [& _] (throw (Exception. "PRIVATE")))]
        (usage/start!)
        (try (capture {:provider "fixture" :model "one" :operation "text"}
                      #(swap! calls inc) (constantly {}))
             (catch Exception e (.getMessage e)))
        => "AI usage tracking is unavailable. No AI request was sent."
        @calls => 0)
      (finally (when flag (reset! @flag original))))))

(fact "the unchanged four-argument handler binds anonymous root context"
  (with-redefs [parser/transformer (fn [_] usage/*context-id*)]
    (boolean (re-matches #"[a-f0-9-]{36}"
                        (handler/handle-parsed-expr {} {} {} []))) => true
    (binding [usage/*context-id* "00000000-0000-0000-0000-000000000001"]
      (handler/handle-parsed-expr {} {} {} [])
      => "00000000-0000-0000-0000-000000000001")))

(defn cli-metrics [result]
  (if-let [f (resolve 'yetibot.core.util.ai-usage/cli-metrics)]
    (f result) ::unimplemented))

(fact "CLI summaries preserve native units and never invent calls or bills"
  (cli-metrics {:exit 0 :response "PRIVATE"
                :stats {:models {:gemini-flash
                                 {:api {:totalRequests 2}
                                  :tokens {:input 9 :cached 3 :prompt 12
                                           :candidates 5 :thoughts 1}}}}})
  => {:state "completed"
      :models [{:model "gemini-flash" :requests 2
                :input_tokens 12 :uncached_tokens 9 :cached_tokens 3
                :output_tokens 5 :reasoning_tokens 1}]}
  (cli-metrics {:exit 0}) => {:state "completed"}
  (cli-metrics {:exit 137 :timed-out true}) => {:state "unknown"})

(fact "optional admission and own receipt run without monthly tracking"
  (let [calls (atom [])
        admit (resolve 'yetibot.core.util.ai-usage/*admit-inference*)
        finish (resolve 'yetibot.core.util.ai-usage/*finish-inference*)]
    (boolean admit) => true
    (boolean finish) => true
    (when (and admit finish)
      (with-redefs [db/connected? (atom false)]
        (with-bindings {admit (fn [attrs] (swap! calls conj (:model attrs)) :own)
                        finish (fn [handle outcome]
                                 (swap! calls conj [handle (:state outcome)]))}
          (capture {:provider "fixture" :model "actual" :operation "text"}
                   (constantly :answer) (constantly {})) => :answer)
        @calls => ["actual" [:own :definitive]]
        (with-bindings {admit (fn [_] (throw (ex-info "denied" {})))}
          (try (capture {} #(swap! calls conj :sent) identity)
               (catch Exception e (.getMessage e))) => "denied")
        (some #{:sent} @calls) => nil))))
