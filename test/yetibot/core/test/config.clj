(ns yetibot.core.test.config
  (:require
    [yetibot.core.config :refer :all]
    [clojure.test :refer :all]))

(defn wrap [f]
  (reload-config)
  (let [original-config @@#'yetibot.core.config/config]
    (f)
    ; reset config back to what it originally was and write to file
    (reset! @#'yetibot.core.config/config original-config)
    (write-config!)))

(use-fixtures :once wrap)

(deftest test-config-is-loaded
  (is (not (nil? (get-config :yetibot)))))

(deftest test-update-config
  (when (config-exists?)
    (update-config :yetibot :foo :bar "baz")
    (is (= "baz" (get-config :yetibot :foo :bar)))
    (reload-config)
    (is (= "baz" (get-config :yetibot :foo :bar)))))
