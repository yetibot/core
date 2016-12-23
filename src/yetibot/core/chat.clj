(ns yetibot.core.chat
  (:require
    [yetibot.core.adapters.adapter :as a]
    [yetibot.core.models.room :as room]
    [taoensso.timbre :refer [debug trace info warn error]]
    [yetibot.core.util.format :as fmt]))

(def ^:dynamic *adapter-uuid*
  "Dynamically set the uuid of an adapter in order to dynamically bind *adapter*
   when it otherwise would not have been bound, such as when an API call is used
   to post a message."
  nil)

(def ^:dynamic *adapter*
  "Dynamically set adapter to dispatch chat functions on. Adapaters should bind
   this when receiving messages to be handled."
  nil)

(def ^:dynamic *target*
  "The target room a chat should be sent to"
  nil)

(defn- validate-sender
  "Does two things:
   - Makes sure an adapter is bound to something truthy. If not, it will try to
     look up the correct adapter using dynamic *adapter-uuid* value.
   - Sends 'No results' message in place of empty message"
  [send-fn]
  (fn [msg]
    (let [msg (str msg)
          msg (if (empty? msg) "No results" msg)]
      (if *adapter*
        (send-fn msg)
        ;; lookup the adapter to bind
        (if *adapter-uuid*
          (binding [*adapter* (get @a/adapters *adapter-uuid*)]
            (send-fn msg))
          (throw (ex-info "Neither *adapter* nor *adapter-uuid* are bound to anything useful"
                          {:adapter *adapter*
                           :adapter-uuid *adapter-uuid*})))))))

(def send-msg (validate-sender #(a/send-msg *adapter* %)))
(def send-paste (validate-sender #(a/send-paste *adapter* %)))
(defn join [room] (a/join *adapter* room))
(defn leave [room] (a/leave *adapter* room))
(defn rooms [] (a/rooms *adapter*))


(defn base-chat-source
  "Data structure representing the chat adapter that a message came from"
  [] {:adapter (keyword (.toLowerCase (a/platform-name *adapter*)))
      :uuid (a/uuid *adapter*)})

(defn chat-source
  "Data structure representing the chat adapter and channel that a message came from"
  [channel] (merge (base-chat-source) {:room channel}))

; (defn set-room-broadcast [room broadcast?] ((:set-room-broadcast *messaging-fns*) room broadcast?))

(def max-msg-count 30)

(defn send-msg-for-each [msgs]
  (doseq [m (take max-msg-count msgs)] (send-msg m))
  (when (> (count msgs) max-msg-count)
    (send-msg (str "Results truncated. There were "
                   (count msgs)
                   " results but I only sent "
                   max-msg-count "."))))

(defn contains-image-url-lines?
  "Returns true if the string contains an image url on its own line, separated from
   other characters by a newline"
  [string]
  (not (empty? (filter #(re-find (re-pattern (str "(?m)^http.*\\." %)) string)
                       ["jpeg" "jpg" "png" "gif"]))))

(defn should-send-msg-for-each?  [d formatted]
  (and (coll? d)
       (<= (count d) 30)
       (re-find #"\n" formatted)
       (contains-image-url-lines? formatted)))

(defn chat-data-structure
  "Formatters to send data structures to chat.
   If `d` is a nested data structure, it will attempt to recursively flatten
   or merge (if it's a map)."
  [d]
  (when-not (:suppress (meta d))
    (let [[formatted flattened] (fmt/format-data-structure d)]
      (debug "formatted is" formatted)
      (debug "flattened is" flattened)
      (cond
        ; send each item in the coll as a separate message if it contains images and
        ; the total length of the collection is less than 20
        (should-send-msg-for-each? d formatted) (send-msg-for-each flattened)
        ; send the message with newlines as a paste
        (re-find #"\n" formatted) (send-paste formatted)
        ; send as regular message
        :else (send-msg formatted)))))

; (defn send-msg-to-all-adapters [msg]
;   (prn "send msg to all" msg)
;   (prn @active-chat-namespaces)
;   (doseq [n @active-chat-namespaces]
;     (when-let [send-to-all (deref (ns-resolve n 'send-to-all))]
;       (send-to-all msg))))



(defn all-rooms []
  "Return a collections of vectors containing:
   - adapter
   - room
   - room settings"
  (apply concat
    (pmap
      (fn [adapter]
        (let [rs (a/rooms adapter)
              uuid (a/uuid adapter)]
          (pmap
            (fn [r]
              (let [settings (room/settings-for-room uuid r)]
                [adapter r settings]))
            rs)))
      (a/active-adapters))))

(defn rooms-with-broadcast []
  (filter
    (fn [[adapter room settings]] (= "true" (get settings "broadcast")))
    (all-rooms)))

(defn broadcast
  "Broadcast message to all rooms who have the broadcast setting enabled"
  [msg]
  (dorun
    (map
      (fn [[adapter room settings]]
        (binding [*target* room]
          (a/send-msg adapter msg)))
      (rooms-with-broadcast))))

; TODO: move hooks/suppress right here

(defn with-target
  "Add target meta data to the data structure to instruct `chat` where to send it"
  [target data-structure]
  (with-meta data-structure {:target target}))
