(ns yetibot.core.commands.room
  (:require
    [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.models.room :as model]
    [yetibot.core.adapters.adapter :as a]
    [yetibot.core.chat :as chat]
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn list-cmd
  "room list # list rooms that yetibot is in"
  [_]
  (chat/rooms))

(defn join-cmd
  "room join <room> # join <room>"
  [{[_ room] :match}]
  (chat/join room))

(defn leave-cmd
  "room leave <room> # leave <room>"
  [{[_ room] :match}]
  (chat/leave room))

(defn settings-for-room [room]
  (model/settings-for-room (a/uuid chat/*adapter*) room))

(defn settings-cmd
  "room settings # show all chat settings for this room"
  [{:keys [chat-source]}]
  (settings-for-room (:room chat-source)))

(defn settings-for-cmd
  "room settings <key> # show the value for a single setting"
  [{[_ k] :match cs :chat-source}]
  (if-let [v (get (settings-for-room (:room cs)) k)]
    v
    (str "'" k "' is not set.")))

(defn set-cmd
  "room set <key> <value> # configure a setting for the current room"
  [{[_ k _ v] :match cs :chat-source}]
  (info "set" k "=" v)
  (model/update-settings (a/uuid chat/*adapter*) (:room cs) k v)
  (str "✓ Set " k " = " v " for this channel."))

(cmd-hook #"room"
  #"settings\s+(\S+)" settings-for-cmd
  #"settings$" settings-cmd
  #"set\s+(\S+)\s+(\=\s+)?(.+)" set-cmd
  #"leave\s+(.+)" leave-cmd
  #"join\s+(.+)" join-cmd
  #"(list)?" list-cmd)
