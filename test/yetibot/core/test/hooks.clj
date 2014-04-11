(ns yetibot.core.test.hooks
  (:require
    [clojure.repl :refer [doc]]
    [yetibot.core.hooks :refer :all]
    [clojure.test :refer :all]))

(defn some-cmd
  "I am some-cmd"
  [_] "some-cmd")

(defn reset []
  (reset! hooks {})
  (reset! re-prefix->topic {})
  [@hooks @re-prefix->topic])

(deftest cmd-hook-and-unhook
  (cmd-hook #"named" _ some-cmd)
  (is (get @hooks "^named$")
      "Hooks should be retrievable by the string representatino of a prefix regex")
  (cmd-unhook "named")
  (is (nil? (get @hooks "^named$"))
      "Hook should no longer be present after being unhooked"))


(deftest cmd-hook-inner-scope
  (let [f (with-meta (fn [_] (prn "inner")) {:doc "inner scoped fn"})]
    (prn (meta f))
    (cmd-hook #"anon"
              _ f)))
