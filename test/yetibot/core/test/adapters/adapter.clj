(ns yetibot.core.test.adapters.adapter
  (:require [yetibot.core.adapters.adapter :as a]
            [midje.sweet :refer [=> fact facts]]))

(facts
 "about register-adapter!"
 (fact
  "registers a custom adapter and is perceived as active"
  ;; verify it doesn't exist
  (a/active-adapters) => nil
  ;; do the thing
  (a/register-adapter! "some-uuid" {:type "test"})
  (a/active-adapters) => '({:type "test"})
  ;; cleanup after myself
  (reset! a/adapters {})
  (a/active-adapters) => nil))

(facts
 "about web-adapter"
 (fact
  "gets the web adapter from the list of registered adapters"
  ;; verify it doesn't exist
  (a/active-adapters) => nil
  ;; do the thing
  (a/register-adapter! "web-uuid" {:config {:type "web" :some "value"}})
  (a/web-adapter) => {:config {:type "web" :some "value"}}
  ;; cleanup after myself
  (reset! a/adapters {})
  (a/active-adapters) => nil))
