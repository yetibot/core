(ns yetibot.core.test.models.history
  (:require
    [clojure.java.jdbc :as sql]
    [yetibot.core.models.history :refer :all]
    [yetibot.core.db :as db]
    [yetibot.core.util :refer [is-command?]]
    [clojure.test :refer :all]))

(def chat-source {:adapter :test :room "foo"})

(defn add-history [body]
  (add {:user-id "test"
        :user-name "test"
        :chat-source-adapter (:adapter chat-source)
        :chat-source-room (:room chat-source)
        :body body}))

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
  ;;     (run! #(add-history (str "body" %)) (range 10))
  ;;
  ;;     ;; add 3 command history items
  ;;     (run! add-history ["!echo" "!status" "!poke"])
  (f))

(use-fixtures :once populate)

(def extra-query {:where/map {:is-yetibot false}})

(deftest test-count-entities
  (count-entities chat-source extra-query))

(deftest test-head
  (head chat-source 2 extra-query))

(deftest test-tail
  (is
    (= 2
       (count (tail chat-source 2 extra-query)))))

(deftest test-random
  (is (map? (random chat-source extra-query))))

(deftest test-grep
  (grep chat-source "b.d+" extra-query))

(deftest test-cmd-only-items
  (cmd-only-items chat-source))

(deftest test-non-cmd-items
  (non-cmd-items chat-source))

(deftest history-should-be-in-order
  ;; TODO
  )

(deftest last-chat-for-channel-test
  (last-chat-for-channel chat-source true)
  (last-chat-for-channel chat-source false))
