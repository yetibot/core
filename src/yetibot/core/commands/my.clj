(ns yetibot.core.commands.my
  (:require [yetibot.core.hooks :refer [cmd-hook]]
            [taoensso.timbre :refer [info]]
            [yetibot.core.db.my :as db]
            [yetibot.core.chat :as chat]))

(defn format-kv [{k :key v :value}] (format "%s: %s" k v))
(defn format-v [{v :value}] (format "%s" v))

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
  "my <key> # get the value of <key> for the current user
   my -a <key> # get the value of <key> for all users in the channel"
  [{[_ all? k] :match {user-id :username} :user}]
  (let [user-query (if all? {} {:user-id user-id})
        result (db/query {:where/map (merge {:key k} user-query)})]
    (if result
      (if all?
        {:result/value (map format-v result)
         :result/data result}
        {:result/value (format-v (first result))
         :result/data (first result)})
      {:result/error (format "No value found for key `%s` and user `%s`"
                             k user-id)})))

(defn list-cmd
  "my # list all known keys for the current user"
  [{{user-id :username} :user}]
  (let [results (db/query {:where/map {:user-id user-id}})]
    (info "list-cmd" (pr-str results))
    {:result/value (map format-kv results)
     :result/data results}))

(comment
  (db/create
   {:user-id "john.doe"
    :chat-source-adapter ":ybslack"
    :chat-source-channel "local"
    :key "zip"
    :value "98104"})
  (db/create
   {:user-id "devth"
    :chat-source-adapter ":ybslack"
    :chat-source-channel "local"
    :key "zip"
    :value "59101"})

  (db/query {:where/map {:key "zip"}})

  (db/find-all))

(cmd-hook #"my"
          #"(\S+)\s+\=\s+(.+)" set-cmd
          #"(-a)?\s*(.+)" get-cmd
          _ list-cmd)

(defn our-cmd
  "our <key> # get the value of <key> for all users in the channel (this is a shortcut for `my -a`)"
  [{k :match}]
  (let [result (db/query {:where/map (merge {:key k})})]
    (if result
      {:result/value (map format-v result)
       :result/data result}
      {:result/error (format "No value found for key `%s`" k)})))

(cmd-hook #"our"
          _ our-cmd)

