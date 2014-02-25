(ns yetibot.core.test.hooks
  (:require
    [clojure.repl :refer [doc]]
    [yetibot.core.hooks :refer :all]
    [clojure.test :refer :all]))

(defn some-cmd
  "I am some-cmd"
  [_] (prn "some-cmd"))

(deftest cmd-hook-with-named-fn
  (cmd-hook #"named"
            _ some-cmd))

(deftest cmd-hook-inner-scope
  (let [f (with-meta (fn [_] (prn "inner")) {:doc "inner scoped fn"})]
    (prn (meta f))
    (cmd-hook #"anon"
              _ f)))
