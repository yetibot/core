(ns yetibot.core.test.hooks
  (:require [yetibot.core.hooks :as h]
            [yetibot.core.observers.karma :refer [hook-wrapper
                                                  message-hook]]
            [clojure.test :refer [function?]]
            [midje.sweet :refer [=> fact facts
                                 contains every-checker]]))

(defn some-cmd
  "I am some-cmd"
  [_] "some-cmd")

(facts "about cmd-hook with defn function"
       (h/cmd-hook #"hookme" _ some-cmd)
       (let [cmd (get @h/hooks "^hookme$")
             cmd-ns (symbol (last cmd))]
         (fact "hooks should be retrievable via valid prefix regex"
               cmd => (every-checker coll? not-empty))
         (fact "and contain the cmd namespace"
               cmd-ns => 'yetibot.core.test.hooks/some-cmd)))

(facts "about cmd-hook with anon function"
       (let [f (with-meta (fn [_] (prn "inner")) {:doc "inner scoped fn"})
             _ (h/cmd-hook #"anonme" _ f)
             cmd (get @h/hooks "^anonme$")]
         (fact "hooks should be retrievable via valid prefix regex"
               cmd => (every-checker coll? not-empty))
         (fact "and contain an anon function"
               (function? (last cmd)) => true)))

(facts "about cmd-unhook"
       (h/cmd-hook #"unhookme" _ some-cmd)
       (h/cmd-unhook "unhookme")
       (let [cmd (get @h/hooks "^unhookme$")]
         (fact "hooks should not be retrievable after being unhook'ed"
               cmd => nil)))

(facts "about find-sub-cmds"
       (let [[prefix-re sub-cmds] (h/find-sub-cmds "channel")]
         (fact "valid find 1st item in tuple is prefix regex"
               prefix-re => "^channel$")
         (fact "valid find 2nd item in tuple is non-empty collection
                with expected contains"
               sub-cmds => (every-checker coll?
                                          not-empty
                                          (contains #"settings$")))
         (fact "invalid prefix returns nil"
               (h/find-sub-cmds "iwillfail") => nil)))

(facts "about cmds-for-cat"
       (let [util-cmds (h/cmds-for-cat "util")
             cmd-symbols (map symbol util-cmds)]
         (fact "results is non-empty set"
               util-cmds => (every-checker set? not-empty))
         (fact "every item is a lang.Var"
               (every? var? util-cmds) => true)
         (fact "results contains expected value"
               cmd-symbols =>
               (contains 'yetibot.core.commands.channel/settings-cmd))))

(facts "about match-sub-cmds"
       (let [re-ns-pairs (last (h/find-sub-cmds "channel"))
             ls-sub-cmd (h/match-sub-cmds "list" re-ns-pairs)]
         (fact "valid match result is non-empty collection"
               ls-sub-cmd => (every-checker coll? not-empty))
         (fact "valid match 1st item is a specific sub-cmd"
               (first ls-sub-cmd) => "list")
         (fact "valid match 2nd item is lang.Var"
               (last ls-sub-cmd) => var?)
         (fact "invalid match returns nil"
               (h/match-sub-cmds "iwillfail" re-ns-pairs) => nil)))

(facts "about split-command-and-args"
       (let [cmd-no-args (h/split-command-and-args "echo")
             cmd-many-arg (h/split-command-and-args
                           "echo hello world how are you today")]
         (fact "command no arg returns two item collection
                where last item is empty"
               cmd-no-args => coll?
               (count cmd-no-args) => 2
               (last cmd-no-args) => empty?)
         (fact "command with many arg returns two item collection
                where last item is not-empty"
               cmd-many-arg => coll?
               (count cmd-many-arg) => 2
               (last cmd-many-arg) => not-empty)))

;; this looks to be a private function, will leave
;; for now and roll up at a later date
(facts "about lockdown-prefix-regex"
       (let [pairs [#"hello" #"^hello$"
                    #"^hello" #"^hello"
                    #"^hello$" #"^hello$"
                    #"hello$" #"hello$"]
             parti-pairs (partition 2 pairs)]
         (fact "lockdown prefix will return expected regex result"
               (doseq [p parti-pairs]
                 (h/lockdown-prefix-regex (first p)) => (last p)))))

(facts "about obs-hook"
       ;;  borrowed example usage from observer.karma
       (let [hooks (h/obs-hook #{:message}
                               (partial hook-wrapper message-hook))]
         (fact "can load message observer hook without error
                and return non-empty collection"
               hooks => (every-checker coll? not-empty))))
