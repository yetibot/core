(ns yetibot.core.test.models.history
  (:require
    [yetibot.core.models.history :refer :all]
    [yetibot.core.db :as db]
    [yetibot.core.util :refer [is-command?]]
    [yetibot.core.config :refer [reload-config]]
    [datomico.core :as dc]
    [datomico.db :refer [q]]
    [clojure.test :refer :all]))

(reload-config "test/resources/test-config.edn")
(db/repl-start)

;; normal query for all records in history
(comment
  (q '[:find ?user-id ?body ?txInstant
       ?chat-source-adapter ?chat-source-room
       :where
       [?tx :db/txInstant ?txInstant]
       [?i :history/user-id ?user-id ?tx]
       [?i :history/chat-source-adapter ?chat-source-adapter ?tx]
       [?i :history/chat-source-room ?chat-source-room ?tx]
       [?i :history/body ?body ?tx]]))


(def chat-source {:adapter :test :room "foo"})

(cmd-only-items chat-source)

(defn add-history [body]
  (add {:user-id "test"
        :user-name "test"
        :chat-source-adapter (:adapter chat-source)
        :chat-source-room (:room chat-source)
        :body body}))

(defonce add-normal-history
  (doall (map #(add-history (str "body" %)) (range 10))))

(defonce add-cmd-history
  (doall
    (map add-history ["!echo" "!status" "!poke"])))

(deftest history-should-be-in-order
  (touch-and-fmt (q (all-entities))))


(deftest last-chat-for-room-test
  (let [cmd (touch-all (last-chat-for-room chat-source true))
        non-cmd (touch-all (last-chat-for-room chat-source false))]
    (println "cmd:" cmd)
    (println "non-cmd:" non-cmd)))

(touch-all (last-chat-for-room chat-source true 3))
