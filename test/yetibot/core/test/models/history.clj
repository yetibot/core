(ns yetibot.core.test.models.history
  (:require [yetibot.core.models.history :as hst]
            [yetibot.core.db :as db]
            [clojure.test :refer :all]
            [midje.sweet :refer [=> fact facts]]))

(def chat-source {:adapter :slack :uuid :test :room "foo"})

;; TODO idempotent db create and teardown to keep test data out of the dev db
;; investigate embedded postgres as a solution:
;; https://eli.naeher.name/embedded-postgres-in-clojure/
;; we need a database
(db/start)

;; Another quick option would be to use a fixture to populate then rollback
;; after the tests, something like:
(defn populate
  [f]
  ;; this would require not using with-db-connection everywhere and having a
  ;; reference to the db instead
  ;; (sql/with-db-transaction [db db]
  ;;   (sql/db-set-rollback-only! db)
  ;;   (binding [db db]
  ;;     ;; add 10 normal history items
  ;;  (run! #(add-history (str "body" %)) (range 10))
  ;;
  ;;     ;; add 3 command history items
  ;;  (run! add-history ["!echo" "!status" "!poke"])
  (f))

(use-fixtures :once populate)

(def extra-query {:where/map {:chat-source-adapter (-> chat-source :uuid pr-str)
                              :is-yetibot false}})

(deftest test-count-entities
  (hst/count-entities extra-query))

(deftest test-head
  (hst/head 2 extra-query))

(deftest test-tail
  (count (hst/tail 2 extra-query)))

(deftest test-random
  (map? (hst/random extra-query)))

(deftest test-grep
  (hst/grep "b.d+" extra-query))

(deftest test-cmd-only-items
  (hst/cmd-only-items chat-source))

(deftest test-non-cmd-items
  (hst/non-cmd-items chat-source))

(deftest last-chat-for-channel-test
  (hst/last-chat-for-channel chat-source true)
  (hst/last-chat-for-channel chat-source false))
