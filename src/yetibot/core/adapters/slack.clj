(ns yetibot.core.adapters.slack
  (:require
    [http.async.client :as c]
    [org.httpkit.client :as http]
    [clj-slack
     [chat :as slack-chat]
     [channels :as channels]
     [rtm :as rtm]]
    [slack-rtm.core :as slack]
    [taoensso.timbre :as log]
    [yetibot.core.config :refer [update-config get-config config-for-ns
                                 reload-config conf-valid?]]
    [yetibot.core.handler :refer [handle-raw]]
    [yetibot.core.chat :refer [chat-data-structure send-msg-for-each
                               register-chat-adapter] :as chat]))

(defn config [] (get-config :yetibot :adapters :slack))

(def conn (atom nil))

(defn slack-config []
  (let [c (config)]
    {:api-url (:endpoint c) :token (:token c)}))

(def ^{:dynamic true
       :doc "the channel or user that a message came from"} *target*)

(defn rooms [] (:rooms (config)))

;;;;

(defn chat-source [channel] {:adapter :slack :room channel})

(defn send-msg [msg]
  (slack-chat/post-message (slack-config) *target* msg
                           {:as_user "true"
                            :username "yetibot"}))

(defn send-paste [msg]
  (send-msg msg))

(def messaging-fns
  {:msg send-msg
   :paste send-paste
   :join nil
   :leave nil
   :set-room-broadcast nil
   :rooms rooms})

(defn on-message [event]
  (log/info "message" event)
  (let [channel (:channel event)]
    (binding [*target* channel
              yetibot.core.chat/*messaging-fns* messaging-fns]
      (handle-raw (chat-source channel)
                  (:user event)
                  :message
                  (:text event)))))

(defn on-hello [event]
  (log/info "hello" event))

(defn on-close [status reason]
  (log/info "close" status reason))

(defn on-error [exception]
  (log/error "error" exception))

(defn on-channel-joined [e]
  (log/info "channel joined" e))

(defn start []
  (reset! conn (slack/connect (slack-config)
                              :channel_joined on-channel-joined
                              :on-error on-error
                              :on-close on-close
                              :message on-message
                              :hello on-hello)))

(defn list-channels [] (channels/list (slack-config)))

;; can't join rooms as a bot - must be invited
; (dorun
;     (map (fn [[channel-name channel-config]]
;            (log/info "join" channel-name
;                      (channels/join (slack-config) channel-name)))
;          (:rooms (config))))
