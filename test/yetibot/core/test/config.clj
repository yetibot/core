(ns yetibot.core.test.config
  (:require
   [midje.sweet :refer [=> fact facts every-checker]]
   [yetibot.core.config :as c]))

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

(facts "about config-from-env-or-file"
       (let [yb-vars (c/config-from-env-or-file {:yetibot {:a 1}}
                                                {:yetibot-b-c 2})]
         (fact "returns expected KV items in non-empty map with
                valid (user defined) YB file cfgs and env cfgs"
               yb-vars => (every-checker map? not-empty)
               (:a yb-vars) => 1
               (:b yb-vars) => (every-checker map? not-empty)
               (get-in yb-vars [:b :c]) => 2)))
