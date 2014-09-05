(ns yetibot.core.commands.room
  (:require
    [yetibot.core.chat :as chat]
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))


(defn format-broadcast [b?]
  (if b? "enabled" "disabled"))

(defn list-cmd
  "room list # list rooms that yetibot is in and whether broadcast is enabled"
  [_]
  (->> (chat/rooms)
      (map (fn [[room opts]]
             (str room ": broadcast " (format-broadcast (:broadcast? opts)))))))

(defn join-cmd
  "room join <room> # join <room>"
  [{[_ room] :match}]
  (chat/join room)
  (str "Joined " room))

(defn leave-cmd
  "room leave <room> # leave <room>"
  [{[_ room] :match}]
  (chat/leave room)
  (str "Left " room))

(defn broadcast-cmd
  "room broadcast <room> # toggle whether yetibot broadcasts to <room> (e.g. incoming Tweets)"
  [{[_ room-name] :match}]
  (if-let [room (get (chat/rooms) room-name)]
    (let [new-broadcast-setting (not (:broadcast? room))]
      (chat/set-room-broadcast room-name new-broadcast-setting)
      (str "Broadcast " (format-broadcast new-broadcast-setting) " for room " room-name))
    (str "Could not find a room for " room-name)))

(cmd-hook #"room"
  #"broadcast\s+(.+)" broadcast-cmd
  #"leave\s+(.+)" leave-cmd
  #"join\s+(.+)" join-cmd
  #"(list)?" list-cmd)
