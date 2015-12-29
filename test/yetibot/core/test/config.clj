(ns yetibot.core.test.config
  (:require
    [yetibot.core.config :refer :all]
    [clojure.test :refer :all]))

(defn wrap [f]
  (reload-config)
  (let [original-config @@#'yetibot.core.config/config]
    ;; run all the tests!!!
    (f)
    ;; after we're done mucking up the config reset config back to what it
    ;; originally was and write back to disk.
    ;; TODO: use a separate config file for tests.
    (reset! @#'yetibot.core.config/config original-config)
    (write-config!)))

(use-fixtures :once wrap)

(deftest test-config-is-loaded
  (is (not (nil? (get-config :yetibot)))))

(deftest test-update-config
  (when (config-exists?)
    (update-config :yetibot :foo :bar "baz")
    (is (= "baz" (get-config :yetibot :foo :bar)))
    ;; re-read from disk
    (reload-config)
    (is (= "baz" (get-config :yetibot :foo :bar)))))

(deftest test-apply-config
  ;; create a new value
  (apply-config [:yetibot :apply-test] (constantly "apply"))
  (is (= "apply" (get-config :yetibot :apply-test)) "apply")
  ;; modify the existing value
  (apply-config
    [:yetibot :apply-test]
    (fn [current-val] (.toUpperCase current-val)))
  (is (= "APPLY" (get-config :yetibot :apply-test))))
