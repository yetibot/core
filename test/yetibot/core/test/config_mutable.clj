(ns yetibot.core.test.config-mutable
  (:require
    [clojure.java.io :refer [delete-file]]
    [yetibot.core.config-mutable :refer :all]
    [schema.core :as s]
    [clojure.test :refer :all]))

(def test-config-path "config/test-config-mutable.edn")

(defn wrap [f]
  ;; ensure config exists
  (reload-config! test-config-path)
  (let [original-config @@#'yetibot.core.config/config]
    ;; run all the tests
    (f)
    ;; delete config file
    (delete-file test-config-path)))

(use-fixtures :once wrap)

(deftest test-config-is-loaded
  (is (not (nil? (get-config {} [:yetibot])))))

(deftest test-update-config
  (is (config-exists? test-config-path))
  (update-config! test-config-path [:yetibot :foo :bar] "baz")
  (is (= "baz" (:value (get-config String [:yetibot :foo :bar]))))
  ;; re-read from disk
  (reload-config! test-config-path)
  (is (= "baz" (:value (get-config String [:yetibot :foo :bar])))))

(deftest test-apply-config
  ;; create a new value
  (apply-config! test-config-path [:yetibot :apply-test] (constantly "apply"))
  (is (= "apply" (:value (get-config String [:yetibot :apply-test]))) "apply")
  ;; modify the existing value
  (apply-config! test-config-path
    [:yetibot :apply-test]
    #(.toUpperCase %))
  (is (= "APPLY" (:value (get-config String [:yetibot :apply-test])))))
