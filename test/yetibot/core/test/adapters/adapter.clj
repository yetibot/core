(ns yetibot.core.test.adapters.adapter
  (:require [yetibot.core.adapters.adapter :as a]
            [midje.sweet :refer [=> fact facts with-state-changes after
                                 provided]]))

(facts
 "about register-adapter!"
 (with-state-changes [(after :facts
                             (reset! a/adapters {}))]
   (fact
    "registers a custom adapter and is perceived as active"
    ;; verify it doesn't exist
    (a/active-adapters) => nil
    ;; register an adapter
    (a/register-adapter! "some-uuid" {:type "test"})
    ;; verify adapter exists
    (a/active-adapters) => '({:type "test"}))))

(facts
 "about web-adapter"
 (fact
  "gets the web adapter from the list of registered adapters"
  (a/web-adapter) => {:config {:type "web" :some "value"}}
  (provided (a/active-adapters) => '({:config {:type "web" :some "value"}}
                                     {:config {:type "random" :some "other value"}}))))

(facts
 "about active adapters"
 (fact
  "gets the vals from the current list of adapters"
  (a/active-adapters {:a1 {:a 1}
                      :b2 {:b 2}}) => '({:a 1}
                                        {:b 2})))
