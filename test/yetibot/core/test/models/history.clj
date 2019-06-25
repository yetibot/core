(ns yetibot.core.test.models.history
  (:require
   [clojure.java.jdbc :as sql]
   [yetibot.core.models.history :refer :all]
   [yetibot.core.db :as db]
   [yetibot.core.util :refer [is-command?]]
   [yetibot.core.midje :refer [value data]]
   [clojure.test.check.generators :as gen]
   [midje.experimental :refer [for-all]]
   [midje.sweet :refer [fact => facts]]))

(def chat-source {:adapter :slack :uuid :test :room "foo"})

(defn add-history [body]
  (add {:user-id "test"
        :user-name "test"
        :chat-source-adapter (:uuid chat-source)
        :chat-source-room (:room chat-source)
        :body body}))

;; TODO idempotent db create and teardown to keep test data out of the dev db
;; investigate embedded postgres as a solution:
;; https://eli.naeher.name/embedded-postgres-in-clojure/
;; we need a database
(defonce db-start (db/start))

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

;; (use-fixtures :once populate)

(def extra-query
  {:where/map {:chat-source-adapter (-> chat-source :uuid pr-str)
               :is-yetibot false}})

(for-all [positive-num gen/s-pos-int]
         (fact "cursor to id transformations are isomorphic"
               (-> positive-num id->cursor cursor->id) => positive-num))

(fact "build query works with cursors"
  (build-query {:cursor (id->cursor 10)}))

(comment
  ;; scratch - these could be ported to tests
  (head 2 extra-query)
  (count (tail 2 extra-query))
  (grep "b.d+" extra-query)
  (cmd-only-items chat-source)
  (non-cmd-items chat-source)
  (last-chat-for-channel chat-source true)
  (last-chat-for-channel chat-source false)
  (map? (random extra-query)))
