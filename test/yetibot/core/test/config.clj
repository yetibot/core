(ns yetibot.core.test.config
  (:require
   [midje.sweet :refer [=> anything against-background
                        fact facts every-checker]]
   [yetibot.core.config :as c]
   [yetibot.core.util.config :as uc]))

(facts "about merge-possible-prefixes"
       (let [merged-cfg (c/merge-possible-prefixes {:yetibot {:a 1}})]
         (fact "returns a merged map of possible prefixes for
                a file with valid prefixes"
               merged-cfg => (every-checker map? not-empty)
               (:a merged-cfg) => 1))
       (let [merged-cfg (c/merge-possible-prefixes {:iwillfail {:a 1}})]
         (fact "returns nil for a map with non-supported prefixes"
               merged-cfg => nil)))

(facts "about prefixed-env-vars"
       (let [yb-env-vars (c/prefixed-env-vars {:yetibot-test true
                                               :skip-me true})]
         (fact "returns expected KV pair in non-empty map with
                valid YB ENV vars"
               yb-env-vars => (every-checker map? not-empty)
               (:yetibot-test yb-env-vars) => true
               (:skip-me yb-env-vars) => nil))
       (let [yb-env-vars (c/prefixed-env-vars {:skip-me true})]
         (fact "returns empty map when no valid YB ENV vars are present"
               yb-env-vars => (every-checker map? empty?))))

(facts
 "about config-from-env-or-file"
 (fact
  "returns expected KV items in non-empty map with valid (user defined)
   YB file cfgs and env cfgs"
  (c/config-from-env-or-file) => (every-checker map? not-empty)
  (:a (c/config-from-env-or-file)) => 1
  (:b (c/config-from-env-or-file)) => (every-checker map? not-empty)
  (get-in (c/config-from-env-or-file) [:b :c]) => 2
  (against-background (uc/load-edn! anything) => {:yetibot {:a 1}}
                      (c/prefixed-env-vars) => {:yetibot-b-c 2})))
