(ns yetibot.core.test.models.history-bounds
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts => contains]]
            [yetibot.core.commands.history :as command]
            [yetibot.core.db.history :as history]
            [yetibot.core.db.util :as db]
            [yetibot.core.models.history :as model]))

(def source {:adapter :slack :uuid :bounds-test :room "current"})

(defn request [next-cmds]
  {:chat-source source :match "--include-history-commands"
   :next-cmds next-cmds :skip-next-n (atom 0)})

(defn with-history [f]
  (let [config (assoc (db/config) :table
                      {:prefix (str "bounds_" (System/nanoTime) "_")})]
    (with-redefs [db/config (constantly config)]
      (let [table (db/qualified-table-name "history")]
        (try
          (jdbc/db-do-commands
            (:url config)
            (jdbc/create-table-ddl
              table (get history/schema :schema/specs)
              {:entities #(string/replace (name %) "-" "_")}))
          ;; Reverse insertion/timestamp order and ties expose missing ORDER BY.
          (doseq [i (range 40 0 -1)]
            (history/create
              {:body (str "match " i) :user-name "alice"
               :chat-source-adapter ":bounds-test"
               :chat-source-room "current" :is-command false
               :created-at (java.sql.Timestamp. (* 1000 (quot i 2)))}))
          (history/create
            {:body "match elsewhere" :chat-source-adapter ":bounds-test"
             :chat-source-room "other" :is-command false})
          (history/create
            {:body "match other adapter" :chat-source-adapter ":other"
             :chat-source-room "current" :is-command false})
          (f (vec (history/query
                    {:where/map {:chat-source-adapter ":bounds-test"
                                 :chat-source-room "current"}
                     :order/clause "created_at ASC, id ASC"})))
          (finally
            (jdbc/db-do-commands (:url config) (str "DROP TABLE " table))))))))

(facts "history bounds are pushed into SQL before rows are materialized"
  (let [queries (atom [])]
    (with-redefs [history/query (fn [q] (swap! queries conj q) [])]
      (command/history-cmd (request []))
      (model/grep "needle" {})
      (fact (mapv :limit/clause @queries) => ["30" "30"])
      (fact (mapv :order/clause @queries)
            => ["created_at DESC, id DESC" "created_at DESC, id DESC"]))))

(facts "real PostgreSQL history ordering, matching, limits and scope"
  (with-history
    (fn [ordered]
      (let [scope {:where/map {:chat-source-adapter ":bounds-test"
                               :chat-source-room "current"}}
            result (command/history-cmd (request []))]
        (fact (vec (:result/data result)) => (subvec ordered 10))
        (fact (vec (model/head 3 scope)) => (subvec ordered 0 3))
        (fact (vec (model/tail 3 scope)) => (subvec ordered 37))
        (fact (vec (model/grep "match" scope)) => (subvec ordered 10))
        (fact (mapv :body (model/grep "^match (1|2)$" scope))
              => ["match 1" "match 2"])
        (fact (model/count-entities scope) => 40)
        (fact (model/head 1 scope) => (first ordered))
        (fact (model/tail 1 scope) => (last ordered))
        (fact (seq (model/head 0 scope)) => nil)))))

(facts "head/tail accept only bounded decimal counts without reader evaluation"
  (doseq [cmd ["head" "tail"]
          n ["-1" "1.5" "1e2" "nil" "1001" "1;DROP" "1 2"
             "9999999999999999999999999" "#=(clojure.core/inc,1)"]]
    (let [calls (atom 0)]
      (with-redefs [history/query (fn [_] (swap! calls inc) [])]
        (fact (command/history-cmd (request [(str cmd " " n)]))
              => (contains {:result/error string?}))
        (fact @calls => 0))))
  (fact (:result/value (command/history-cmd (request ["head 0"]))) => []))

(fact "pipeline grep preserves significant whitespace in its regex"
  (let [query (atom nil)]
    (with-redefs [history/query (fn [q] (reset! query q) [])]
      (command/history-for-cmd-sequence ["grep two  spaces"] {})
      (:where/args @query) => ["two  spaces"])))

(facts "model-owned bounds and order replace caller query hints"
  (with-history
    (fn [ordered]
      (let [q {:where/map {:chat-source-adapter ":bounds-test"
                           :chat-source-room "current"}
               :order/clause "body DESC" :limit/clause "10000"}]
        (fact (vec (model/head 2 q)) => (subvec ordered 0 2))
        (fact (vec (model/tail 2 q)) => (subvec ordered 38))
        (fact (vec (model/grep "match" q)) => (subvec ordered 10))))))
