(ns yetibot.core.test.util.config
  (:require
   [midje.sweet :refer [=> fact facts every-checker]]
   [yetibot.core.util.config :as c]
   [yetibot.core.loader :as ldr]))

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
         (fact "get's invalid map path and tests against valid spec"
               (:error (c/get-config cfg
                                     ::ldr/url [:yetibot :doesnotexist]))
               => :not-found)
         (fact "get's valid map path and tests against invalid spec"
               (:error (c/get-config cfg
                                     ::ldr/plugin-config [:yetibot :url]))
               => :invalid)))
