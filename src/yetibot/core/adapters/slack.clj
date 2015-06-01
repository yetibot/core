(ns yetibot.core.adapters.slack
  (:require
    [gniazdo.core :as ws]
    [http.async.client :as c]
    [org.httpkit.client :as http]
    [yetibot.core.models.users :as users]
    [clj-slack
     [users :as slack-users]
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

(defonce conn (atom nil))

(defn self
  "Slack acount for yetibot from `rtm.start` (represented at (-> @conn :start)).
   You must call `start` in order to define `conn`."
  []
  (-> @conn :start :self))

(defn slack-config []
  (let [c (config)]
    {:api-url (:endpoint c) :token (:token c)}))

(def ^{:dynamic true
       :doc "the channel or user that a message came from"} *target*)

(defn rooms [] (:rooms (config)))

;;;;

(def adapter :slack)

(defn chat-source [channel] {:adapter adapter :room channel})

(defn send-msg [msg]
  (slack-chat/post-message (slack-config) *target* msg
                           {:as_user "true"}))

(defn send-paste [msg]
  (send-msg msg))

(def messaging-fns
  {:msg send-msg
   :paste send-paste
   :join nil
   :leave nil
   :set-room-broadcast nil
   :rooms rooms})

;; events

(defn on-message [event]
  ;; don't listen to yetibot's own messages
  (when (not= (:id (self)) (:user event))
    (log/info "message" event)
    (let [channel (:channel event)]
      (binding [*target* channel
                yetibot.core.chat/*messaging-fns* messaging-fns]
        (handle-raw (chat-source channel)
                    (:user event)
                    :message
                    (:text event))))))

(defn on-hello [event]
  (log/info "hello" event))

(defn on-connect [e]
  (log/info "connect" e))

(defn on-close [status]
  (log/info "close" status))

(defn on-error [exception]
  (log/error "error" exception))

(defn on-channel-joined [e]
  (log/info "channel joined" e))

(defn handle-presence-change [e]
  )

(defn on-presence-change [e]
  (log/info "presence changed" e)
  (handle-presence-change e))

(defn on-manual-presence-change [e]
  (log/info "manual presence changed" e)
  (handle-presence-change e))

;; users

(defn filter-chans-or-grps-containing-user [user-id chans-or-grps]
  (filter #((-> % :members set) user-id) chans-or-grps))

(defn reset-users-from-conn []
  (let [groups (-> @conn :start :groups)
        channels (-> @conn :start :channels)
        users (-> @conn :start :users)]
    (dorun
      (map
        (fn [{:keys [id] :as user}]
          (let [filter-for-user (partial filter-chans-or-grps-containing-user id)
                ; determine which channels and groups the user is in
                chans-or-grps-for-user (concat (filter-for-user channels)
                                               (filter-for-user groups))
                active? (= "active" (:presence user))
                ; turn the list of chans-or-grps-for-user into a list of chat sources
                chat-sources (set (map (comp chat-source :id) chans-or-grps-for-user))
                ; create a user model
                user-model (users/create-user (:name user) active? user)]
            (dorun
              ; for each chat source add a user individually
              (map (fn [cs] (users/add-user cs user-model)) chat-sources))))
        users))))


(let [chat-source {:adapter :slack :room :lol}]
  (merge-with
    (fn [existing-user new-user] (update-in existing-user [:rooms] conj chat-source))
    {1 {:rooms #{{:adapter :slack :room :lol}}}}
    {2 {:username "foo"}}
    ))


;; start/stop

(defn stop []
  (when @conn
    (slack/send-event (:dispatcher @conn) :close)))

(defn start []
  (stop)
  (reset! conn (slack/connect (slack-config)
                              :channel_joined on-channel-joined
                              :on-connect on-connect
                              :on-error on-error
                              :on-close on-close
                              :presence_change on-presence-change
                              :manual_presence_change on-manual-presence-change
                              :message on-message
                              :hello on-hello))

  (reset-users-from-conn))

(defn list-channels [] (channels/list (slack-config)))

;; can't join rooms as a bot - must be invited
; (dorun
;     (map (fn [[channel-name channel-config]]
;            (log/info "join" channel-name
;                      (channels/join (slack-config) channel-name)))
;          (:rooms (config))))
