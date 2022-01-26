(ns yetibot.core.test.util.config
  (:require
   [midje.sweet :refer [=> fact facts every-checker with-state-changes
                        after contains]]
   [yetibot.core.util.config :as c]
   [yetibot.core.loader :as ldr]
   [clojure.java.io :as io]))

(facts "about load-edn!"
       (fact "loading non-existent config file returns nil"
             (c/load-edn! "nope.edn") => nil)
       (let [cfg (c/load-edn! "config/config.sample.edn")]
         (fact "loading sample config file returns non-empty map"
               cfg => (every-checker map? not-empty))
         (fact "loading sample config file returns expected value"
               (get-in cfg [:yetibot :url])
               => "http://localhost:3003")))

(facts "about get-config"
       (let [cfg (c/load-edn! "config/config.sample.edn")]
         (fact "get's valid map path and tests against spec"
               (c/get-config cfg ::ldr/url [:yetibot :url])
               => {:value "http://localhost:3003"})
         (fact "get's valid map path (not in a collection) and tests against
                spec"
               (c/get-config {:url "http://localhost:3003"}
                             ::ldr/url :url)
               => {:value "http://localhost:3003"})
         (fact "get's invalid map path and tests against valid spec"
               (:error (c/get-config cfg
                                     ::ldr/url [:yetibot :doesnotexist]))
               => :not-found)
         (fact "get's valid map path and tests against invalid spec"
               (:error (c/get-config cfg
                                     ::ldr/plugin-config [:yetibot :url]))
               => :invalid)))

(facts
 "about config-exists?"
 (fact
  "it will return false when the supplied path does not exist on the file system
   and will return true when the supplied path does exist on the file system"
  (c/config-exists? "node.edn") => false
  (c/config-exists? "config/config.sample.edn") => true))

(facts
 "about load-or-create-edn!"
 (with-state-changes [(after :facts (io/delete-file "config/node-test.edn"))]
   (fact
    "it will create the config file if it doesn't exist and write to it the
     default config values"
    (c/load-or-create-edn! "config/node-test.edn") => c/default-config
    (c/config-exists? "config/node-test.edn") => true))

 (fact
  "it will catch an exception when trying to load/create the config, log it,
   and return nil"
  (c/load-or-create-edn! (Exception. "i will fail")) => nil)

 (fact
  "it will load the config file if it does exist and return the hash map >>
   small spot check on values in the config.sample.edn file"
  (:yetibot (c/load-or-create-edn! "config/config.sample.edn"))
  => (contains {:alphavantage {:key ""}}
               {:default {:command "giphy"}}
               {:nrepl {:port ""}})))
