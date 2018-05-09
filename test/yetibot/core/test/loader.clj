(ns yetibot.core.test.loader
  (:require
    [yetibot.core.loader :as loader]
    [clojure.test :refer :all]))

(deftest loader
  (testing
    "Loading all namespaces. This can help find invalid requires or errors in
    code at test time (instead of waiting till runtime)"
    (loader/load-observers)
    (loader/load-commands)))
