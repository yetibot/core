(ns yetibot.core.commands.channel
  (:require
    [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.models.channel :as model]
    [yetibot.core.adapters.adapter :as a]
    [yetibot.core.chat :as chat]
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn list-cmd
  "channel list # list channels that yetibot is in"
  {:yb/cat #{:util}}
  [_]
  (let [channels (chat/channels)]
    {:result/value channels
     :result/data channels}))

(defn join-cmd
  "channel join <channel> # join <channel>"
  {:yb/cat #{:util}}
  [{[_ channel] :match}]
  (chat/join channel))

(defn leave-cmd
  "channel leave <channel> # leave <channel>"
  {:yb/cat #{:util}}
  [{[_ channel] :match}]
  (chat/leave channel))

(defn settings-for-channel [channel]
  (model/channel-settings (a/uuid chat/*adapter*) channel))

(defn settings-cmd
  "channel settings # show all chat settings for this channel"
  {:yb/cat #{:util}}
  [{:keys [chat-source]}]
  (let [settings (settings-for-channel (:room chat-source))]
    {:result/value settings
     :result/data settings}))

(defn settings-for-cmd
  "channel settings <key> # show the value for a single setting"
  {:yb/cat #{:util}}
  [{[_ k] :match cs :chat-source}]
  (if-let [v (get (settings-for-channel (:room cs)) k)]
    v
    {:result/error (str "'" k "' is not set.")}))

(defn set-cmd
  "channel set <key> <value> # configure a setting for the current channel"
  {:yb/cat #{:util}}
  [{[_ k _ v] :match cs :chat-source}]
  (info "set" k "=" v)
  (if (model/protected-keys k)
    {:result/error
     (str "`" k "` is a protected key and cannot be manually set")}
    (let [result (model/set-key (a/uuid chat/*adapter*) (:room cs) k v)]
      {:result/value (str "✓ Set " k " = " v " for this channel.")
       :result/data result}
      )))

(defn unset-cmd
  "channel set <key> <value> # configure a setting for the current channel"
  {:yb/cat #{:util}}
  [{[_ k] :match cs :chat-source}]
  (info "unset" k)
  (if (model/protected-keys k)
    {:result/error
     (str "`" k "` is a protected key and cannot be manually unset")}
    (if-let [result (model/unset-key (a/uuid chat/*adapter*) (:room cs) k)]
      {:result/value (str "✓ Unset " k " for this channel.")
       :result/data result}
      {:result/error
       (str "Key `" k "` is not set on the " (:room cs) " channel")})))

(cmd-hook ["channel" #"^channel|room$"]
  #"settings\s+(\S+)" settings-for-cmd
  #"settings$" settings-cmd
  #"set\s+(\S+)\s+(\=\s+)?(.+)" set-cmd
  #"unset\s+(\S+)" unset-cmd
  #"leave\s+(.+)" leave-cmd
  #"join\s+(.+)" join-cmd
  #"list" list-cmd)
