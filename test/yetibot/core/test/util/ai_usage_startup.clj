(ns yetibot.core.test.util.ai-usage-startup
  (:require [midje.sweet :refer [fact =>]]
            [yetibot.core.adapters :as adapters]
            [yetibot.core.db :as db]
            [yetibot.core.init :as init]
            [yetibot.core.loader :as loader]
            [yetibot.core.logging :as logging]
            [yetibot.core.monitoring :as monitoring]
            [yetibot.core.util.ai-usage :as usage]))

(fact "an unavailable rollout marker stops adapters and background job loading"
  (let [calls (atom [])
        forbidden (fn [& _] (throw (AssertionError. "No activation before ledger")))]
    (with-redefs [init/welcome-message! (fn [])
                  usage/require-tracking! #(swap! calls conj :required)
                  init/start-nrepl! #(swap! calls conj :nrepl)
                  db/start #(swap! calls conj :database)
                  usage/start! #(do (swap! calls conj :ledger)
                                    (throw (Exception. "tracking unavailable")))
                  logging/start forbidden monitoring/start forbidden
                  adapters/start forbidden loader/load-all forbidden]
      (try (init/-main) (catch Exception _ :blocked)) => :blocked
      @calls => [:required :nrepl :database :ledger])))
