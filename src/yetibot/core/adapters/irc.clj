(ns yetibot.core.adapters.irc
  (:require
    [taoensso.timbre :as log]
    [rate-gate.core :refer [rate-limit]]
    [yetibot.core.chat]
    [irclj
     [core :as irc]
     [connection :as irc-conn]]
    [yetibot.core.models.users :as users]
    [clojure.string :refer [split-lines join]]
    [yetibot.core.config :refer [get-config config-for-ns conf-valid?]]
    [yetibot.core.chat :refer [send-msg-for-each register-chat-adapter]]
    [yetibot.core.util.format :as fmt]
    [yetibot.core.handler :refer [handle-raw]]))

(defonce conn (atom nil))

(declare connect start)

(defn config [] (get-config :yetibot :adapters :irc))

(defn channels [] (:channels (config)))

(defn chat-source [channel] {:adapter :irc :channel channel})

(def wait-before-reconnect 30000)

(def irc-max-message-length 420)

(defn split-msg-into-irc-max-length-chunks [msg]
  (map join (partition-all irc-max-message-length msg)))

(def ^{:dynamic true
       :doc "the channel or user that a message came from"} *target*)

(def send-msg
  "Rate-limited function for sending messages to IRC. It's rate limited in order
   to prevent 'Excess Flood' kicks"
  (rate-limit
    (fn [msg]
      (log/info "send message to channel" *target*)
      (try
        (if (> (count msg) irc-max-message-length)
          (doall (map send-msg (split-msg-into-irc-max-length-chunks msg)))
          (irc/message @conn *target* msg))
        (catch java.net.SocketException e
          ; it must have disconnect, try reconnecting again
          (log/info "SocketException, trying to reconnect in" wait-before-reconnect "ms")
          (Thread/sleep wait-before-reconnect)
          (connect)
          (start))))
    3 900))

(defn- create-user [info]
  (let [username (:nick info)
        id (:user info)]
    (users/create-user username (merge info {:id id}))))

(def prepare-paste
  "Since pastes are sent as individual messages, blank lines would get
   translated into \"No Results\" by the chat namespace. Instead of a blank
   line, map it into a single space."
  (comp (fn [coll] (map #(if (empty? %) " " %) coll))
        split-lines))

(defn send-paste
  "In IRC there are new newlines. Each line must be sent as a separate message, so
   split it and send one for each"
  [p] (send-msg-for-each (prepare-paste p)))

(defn fetch-users []
  (doall (map #(irc-conn/write-irc-line @conn "WHO" %) (channels))))

(def messaging-fns
  {:msg send-msg
   :paste send-paste})

(defn recognized-chan? [c] ((set (channels)) c))

; example info args for handle-message
; - priv msg
; {:text !echo hi, :target yetibotz, :command PRIVMSG, :params [yetibotz !echo hi],
;  :raw :devth!~devth@1.1.1.1 PRIVMSG yetibotz :!echo hi, :host 2.2.2.2,
;  :user ~devth, :nick devth}
; - message from #yeti channel
; {:text !echo ook, :target #yeti, :command PRIVMSG, :params [#yeti !echo ook],
;  :raw :devth!~devth@1.1.1.1 PRIVMSG #yeti :!echo ook, :host 2.2.2.2,
;  :user ~devth, :nick devth}

(defn handle-message
  "Recieve and handle messages from IRC. This can either be in channels yetibot
   is listening in, or it can be a private message. If yetibot does not
   recognize the :target, reply back to user with PRIVMSG."
  [_ info]
  (log/info "handle message" info)
  (let [user-id (:user info)
        chan (or (recognized-chan? (:target info)) (:nick info))
        user (users/get-user (chat-source chan) user-id)]
    (binding [*target* chan
              yetibot.core.chat/*messaging-fns* messaging-fns]
      (handle-raw (chat-source chan) user :message (:text info)))))

(defn handle-part [_ info]
  (handle-raw (chat-source (:target info))
              (create-user info) :leave nil))

(defn handle-join [_ info]
  (handle-raw (chat-source (:target info))
              (create-user info) :enter nil))

(defn handle-nick [_ info]
  (let [[nick] (:params info)
        id (:user info)]
    (users/update-user (chat-source (:target info)) id {:username nick :name nick})))

(defn handle-who-reply [_ info]
  (log/debug "352" info)
  (let [{[_ channel user _ _ nick] :params} info]
    (log/info "add user" channel user nick)
    (users/add-user (chat-source channel)
                    (create-user {:user user :nick nick}))))

(defn raw-log [a b c] #_(debug b c))

(defn handle-end-of-names
  "Callback for end of names list from IRC. Currently not doing anything with it."
  [irc event]
  (let [users (-> @irc :channels vals first :users)]))

(def callbacks {:privmsg #'handle-message
                :raw-log #'raw-log
                :part #'handle-part
                :join #'handle-join
                :nick #'handle-nick
                :366 #'handle-end-of-names
                :352 #'handle-who-reply})

(defn connect []
  (reset!
    conn
    (irc/connect
      (:host (config)) (read-string (or (:port (config)) "6667")) (:username (config))
      :callbacks callbacks)))

(defn start
  "Join and fetch all users with WHO <channel>"
  []
  (if (conf-valid? (config))
    (do
      (register-chat-adapter 'yetibot.core.adapters.irc)
      (connect)
      (doall (map #(irc/join @conn %) (channels)))
      (fetch-users))
   (log/info "✗ IRC is not configured")))

(defn part [channel]
  (when conn (irc/part @conn channel)))
