(ns yetibot.core.test.models.history
  (:require [yetibot.core.models.history :as hst]
            [yetibot.core.db :as db]
            [midje.sweet :refer [=> fact facts]]))

(def chat-source {:adapter :slack :uuid :test :room "foo"})

;; TODO idempotent db create and teardown to keep test data out of the dev db
;; investigate embedded postgres as a solution:
;; https://eli.naeher.name/embedded-postgres-in-clojure/
;; we need a database
(db/start)

(def extra-query {:where/map {:chat-source-adapter (-> chat-source :uuid pr-str)
                              :is-yetibot false}})

(facts
 "about core.models.history"
 (fact
  "it can count the number of entities without error"
  (hst/count-entities extra-query) => int?)
 (fact
  "it can get the first lines of history without error"
  (hst/head 2 extra-query))
 (fact
  "it can get the last lines of history without error"
  (hst/tail 2 extra-query))
 (fact
  "it can get history and show in random order without error"
  (hst/random extra-query))
 (fact
  "it can search history for a specific pattern without error"
  (hst/grep "b.d+" extra-query))
 (fact
  "it can get the history of commands only without error"
  (hst/cmd-only-items chat-source))
 (fact
  "it can get the history of non-commands without error"
  (hst/non-cmd-items chat-source))
 (fact
  "it can get the history of commands for a specific chat source without
    error"
  (hst/last-chat-for-channel chat-source true))
 (fact
  "it can get the history of 'normal chat' for a specific chat source
    without error"
  (hst/last-chat-for-channel chat-source false)))
