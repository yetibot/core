(ns yetibot.core.test.loader
  (:require [yetibot.core.loader :as loader]
            [midje.sweet :refer [=> =not=> contains fact facts every-checker]]))

(fact "all-namespaces returns non-empty collection that contains expected namespace"
      (loader/all-namespaces)
      => (every-checker coll? not-empty (contains 'yetibot.core.loader)))

(facts "about load-ns"
       (fact "loads a legit namesapce and returns said namespace"
             (loader/load-ns 'yetibot.core.commands.help)
             => 'yetibot.core.commands.help)
       (fact "returns nil when loading illegitimate namespace"
             (loader/load-ns 'i.am.fake) => nil))

(facts "about find-and-load-namespaces"
       (let [patterns '(#"yetibot\.core\.commands\.help"
                        #"yetibot\.core\.commands\.error")
             nss (loader/find-and-load-namespaces patterns)
             nss-count (count nss)]
         (fact "results is non-empty collection that contains expected namespace"
               nss => (every-checker coll? not-empty) (contains 'yetibot.core.commands.help))
      ;;    (fact "results is exactly 2 commands"
      ;;          nss-count => 2)
         (fact "results don't contain extraneous namespaces"
               nss =not=> (contains 'yetibot.core.commands.echo))))

(fact "load-observers returns a non-empty collection"
      (loader/load-observers) => (every-checker coll? not-empty))

(fact "load-commands returns a non-empty collection"
      (loader/load-commands) => (every-checker coll? not-empty))
