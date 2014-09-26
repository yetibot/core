(ns yetibot.core.test.models.history
  (:require
    [yetibot.core.models.history :refer :all]
    [datomico.core :as dc]
    [datomico.db :refer [q]]
    [clojure.test :refer :all]))

;; normal query for all records in history
(q '[:find ?user-id ?body ?txInstant
     ?chat-source-adapter ?chat-source-room
     :where
     [?tx :db/txInstant ?txInstant]
     [?i :history/user-id ?user-id ?tx]
     [?i :history/chat-source-adapter ?chat-source-adapter ?tx]
     [?i :history/chat-source-room ?chat-source-room ?tx]
     [?i :history/body ?body ?tx]])

(defn is-command? [h] (re-find #"^\!" h))
