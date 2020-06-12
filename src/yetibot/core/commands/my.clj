(ns yetibot.core.commands.my
  (:require [yetibot.core.hooks :refer [cmd-hook]]
            [taoensso.timbre :refer [info]]
            [yetibot.core.db.my :as db]))

(defn format-kv
  [{k :key v :value}]
  (format "%s: %s" k v))

(defn set-cmd
  "my <key> = <value> # set <key> = to <value> for the current user"
  [{[_ k v] :match
    {user-id :username} :user
    {chat-source-channel :room
     chat-source-uuid :uuid} :chat-source}]
  (let [record {:user-id user-id
                :chat-source-adapter (pr-str chat-source-uuid)
                :chat-source-channel chat-source-channel
                :key k
                :value v}]
    (info "set-cmd" (pr-str record))
    (db/create record)
    (format "✓ Set %s = %s for %s" k v user-id)))

(defn get-cmd
  "my <key> # get the value of <key> for the current user"
  [{k :match {user-id :username} :user}]
  (let [[result] (db/query {:where/map {:key k}})]
    (if result
      {:result/value (format-kv result)
       :result/data result}
      {:result/error (format "No value found for key `%s` and user `%s`"
                             k user-id)})))
(defn list-cmd
  "my # list all known keys for the current user"
  [{{user-id :username} :user}]
  (let [results (db/query {:user-id user-id})]
    {:result/value (map format-kv results)
     :result/data results}))

(comment
  (db/create
   {:user-id "devth"
    :chat-source-adapter ":ybslack"
    :chat-source-channel "local"
    :key "zip"
    :value "59101"})

  (db/query {:where/map {:key "nope"}})

  (db/find-all))

(cmd-hook #"my"
          #"(\S+)\s+\=\s+(.+)" set-cmd
          #".+" get-cmd
          _ list-cmd)
