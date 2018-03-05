(ns yetibot.core.adapters.slack
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.core.memoize :as memo]
    [yetibot.core.adapters.adapter :as a]
    [robert.bruce :refer [try-try-again] :as rb]
    [gniazdo.core :as ws]
    [clojure.string :as s]
    [schema.core :as sch]
    [yetibot.core.interpreter :refer [*chat-source*]]
    [yetibot.core.models.users :as users]
    [yetibot.core.util.http :refer [html-decode]]
    [clj-slack
     [users :as slack-users]
     [im :as im]
     [chat :as slack-chat]
     [channels :as channels]
     [groups :as groups]
     [reactions :as reactions]
     [rtm :as rtm]]
    [slack-rtm.core :as slack]
    [taoensso.timbre :as timbre :refer [color-str debug info warn error]]
    [yetibot.core.config-mutable :refer [get-config apply-config!]]
    [yetibot.core.handler :refer [handle-raw]]
    [yetibot.core.chat :refer [base-chat-source chat-source
                               chat-data-structure *thread-ts* *target* *adapter*]]
    [yetibot.core.util :as utl]))

(def channel-cache-ttl 60000)

(defn slack-config
  "Transforms yetibot config to expected Slack config"
  [config]
  {:api-url (or (:endpoint config) "https://slack.com/api")
   :token (:token config)})

(defn list-channels [config] (channels/list (slack-config config)))

(def channels-cached
  (memo/ttl
    (comp :channels list-channels)
    :ttl/threshold channel-cache-ttl))

(defn channel-by-id [id config]
  (first (filter #(= id (:id %)) (channels-cached config))))


(defn list-groups [config] (groups/list (slack-config config)))

(defn channels-in
  "all channels that yetibot is a member of"
  [config]
  (filter :is_member (:channels (list-channels config))))

(defn self
  "Slack acount for yetibot from `rtm.start` (represented at (-> @conn :start)).
   You must call `start` in order to define `conn`."
  [conn]
  (-> @conn :start :self))

;;;;

(defn chan-or-group-name
  "Takes either a channel or a group and returns its name as a String.
   If it's a public channel, # is prepended.
   If it's a group, just return the name."
  [chan-or-grp]
  (str (when (:is_channel chan-or-grp) "#")
       (:name chan-or-grp)))

(defn rooms
  "A vector of channels and any private groups by name"
  [config]
  (concat
    (map :name (:groups (list-groups config)))
    (map #(str "#" (:name %)) (channels-in config))))

(defn send-msg [config msg]
  (debug "send-msg"
         (color-str :blue (pr-str config))
         {:target *target*
          :thread-ts *thread-ts*})
  (slack-chat/post-message
    (slack-config config) *target* msg
    (merge
      {:unfurl_media "true" :as_user "true"}
      (when *thread-ts*
        {:thread_ts *thread-ts*}))))

(defn send-paste [config msg]
  (slack-chat/post-message
    (slack-config config) *target* ""
    {:unfurl_media "true" :as_user "true"
     :attachments [{:pretext "" :text msg}]}))

;; formatting

(defn unencode-message
  "Slack gets fancy with URL detection, channel names, user mentions, as
   described in https://api.slack.com/docs/formatting. This can break support
   for things where YB is expecting a URL (e.g. configuring Jenkins), so strip
   it for now. Replaces <X|Y> with Y.

   Secondly, as of 2017ish first half, Slack started mysteriously encoding
   @here and @channel as <!here> and <!channel>. Wat. DECODE THAT NOISE.

   <!here> becomes @here
   <!channel> becomes @channel"
  [body]
  (-> body
    (s/replace  #"\<\!(here|channel)\>" "@$1")
    (s/replace #"\<(.+)\|(\S+)\>" "$2")
    (s/replace #"\<(\S+)>" "$1")
    html-decode))

(defn entity-with-name-by-id
  "Takes a message event and translates a channel ID, group ID, or user id from
   a direct message (e.g. 'C12312', 'G123123', 'D123123') into a [name entity]
   pair. Channels have a # prefix"
  [config event]
  (let [sc (slack-config config)
        chan-id (:channel event)]
    (condp = (first chan-id)
      ;; direct message - lookup the user
      \D (let [e (:user (slack-users/info sc (:user event)))]
           [(:name e) e])
      ;; channel
      \C (let [e (:channel (channels/info sc chan-id))]
           [(str "#" (:name e)) e])
      ;; group
      \G (let [e (:group (groups/info sc chan-id))]
           [(:name e) e])
      (throw (ex-info "unknown entity type" event)))))


;; events

(defn on-channel-join [{:keys [channel] :as e} config]
  (let [[chan-name entity] (entity-with-name-by-id config {:channel channel})
        cs (chat-source chan-name)
        user-model (users/get-user cs (:user e))]
    (binding [*target* channel]
      (timbre/info "channel join" (color-str :blue (with-out-str (pprint cs))))
      (handle-raw cs user-model :enter nil))))

(defn on-channel-leave [{:keys [channel] :as e} config]
  (let [[chan-name entity] (entity-with-name-by-id config {:channel channel})
        cs (chat-source chan-name)
        user-model (users/get-user cs (:user e))]
    (binding [*target* channel]
      (timbre/info "channel leave" e)
      (handle-raw cs user-model :leave nil))))

(defn on-message-changed [{:keys [channel] {:keys [user text]} :message} config]
  (timbre/info "message changed")
  (let [[chan-name entity] (entity-with-name-by-id config {:channel channel
                                                           :user user})
        cs (chat-source chan-name)
        user-model (users/get-user cs user)]
    (binding [*target* channel]
      (handle-raw cs
                  user-model
                  :message
                  (unencode-message text)))))

(defn on-message [conn config {:keys [subtype] :as event}]
  (timbre/info "message" event)
  (if (and (not= "bot_mesage" subtype) subtype)
    (do
      (info "event subtype" subtype)
      ; handle the subtype
      (condp = subtype
        "channel_join" (on-channel-join event config)
        "channel_leave" (on-channel-leave event config)
        "message_changed" (on-message-changed event config)
        ; do nothing if we don't understand
        (info "Don't know how to handle message subtype" subtype)))
    ; don't listen to yetibot's own messages
    (if (not= (:id (self conn)) (:user event))
      (let [{chan-id :channel thread-ts :thread_ts} event
            [chan-name entity] (entity-with-name-by-id config event)
            ;; TODO we probably need to switch to chan-id when building the
            ;; Slack chat-source since they are moving away from being able to
            ;; use user names as IDs
            cs (chat-source chan-name)
            user-model (users/get-user cs (:user event))]
        (binding [*thread-ts* thread-ts
                  *target* chan-id]
          (handle-raw cs
                      user-model
                      :message
                      (unencode-message (:text event)))))
      (debug "Ignoring message from Yetibot" (:user event)))))

(defn on-hello [event] (timbre/debug "Hello, you are connected to Slack" event))

(defn on-connect [e] (timbre/debug "connected"))

(declare restart)

;; See https://developer.mozilla.org/en-US/docs/Web/API/CloseEvent for the full
;; list of status codes on a close event
(def status-normal-close 1000)

(defn on-close [conn config {:keys [status-code] :as status}]
  (timbre/info "close" (:name config) status)
  (when (not= status-normal-close status-code)
    (try-try-again
      {:decay 1.1 :sleep 5000 :tries 500}
      (fn []
        (timbre/info "attempt no." rb/*try* " to rereconnect to slack")
        (when rb/*error* (timbre/info "previous attempt errored:" rb/*error*))
        (when rb/*last-try* (timbre/warn "this is the last attempt"))
        (restart conn config)))))

(defn on-error [exception]
  (timbre/error "error" exception))

(defn handle-presence-change [e]
  (let [active? (= "active" (:presence e))
        id (:user e)
        source (select-keys (base-chat-source) [:adapter])]
    (when active?
      (debug "User is active:" (pr-str e)))
    (users/update-user source id {:active? active?})))

(defn on-presence-change [e]
  (handle-presence-change e))

(defn on-manual-presence-change [e]
  (timbre/debug "manual presence changed" e)
  (handle-presence-change e))

(defn room-persist-edn
  "Update config.edn when a room is joined or left"
  [uuid room joined?]
  (if joined?
    ;; add
    (apply-config!
      [:yetibot :adapters uuid :rooms]
      (fn [x] (conj room)))
    ;; remove
    (apply-config!
      [:yetibot :adapters "uuid" :rooms]
      (fn [x]
        (disj (set x) "foo")))))

(defn on-channel-joined
  "Fires when yetibot gets invited and joins a channel or group"
  [e]
  (timbre/debug "channel joined" e)
  (let [c (:channel e)
        {:keys [uuid room] :as cs} (chat-source (:id c))
        user-ids (:members c)]
    (room-persist-edn uuid room true)
    (timbre/debug "adding chat source" cs "for users" user-ids)
    (dorun (map #(users/add-chat-source-to-user cs %) user-ids))))

(defn on-channel-left
  "Fires when yetibot gets kicked from a channel or group"
  [e]
  (timbre/debug "channel left" e)
  (let [c (:channel e)
        {:keys [uuid room] :as cs} (chat-source c)
        users-in-chan (users/get-users cs)]
    (room-persist-edn uuid room false)
    (timbre/debug "remove users from" cs (map :id users-in-chan))
    (dorun (map (fn [u] (users/remove-user cs (:id u))) users-in-chan))))

;; users

(defn filter-chans-or-grps-containing-user [user-id chans-or-grps]
  (filter #((-> % :members set) user-id) chans-or-grps))

(defn reset-users-from-conn [conn]
  (let [groups (-> @conn :start :groups)
        channels (-> @conn :start :channels)
        users (-> @conn :start :users)]
    (debug (timbre/color-str :blue "reset-users-from-conn")
           (pr-str (count users)))
    (run!
      (fn [{:keys [id] :as user}]
        (let [filter-for-user (partial filter-chans-or-grps-containing-user id)
              ; determine which channels and groups the user is in
              chans-or-grps-for-user (concat (filter-for-user channels)
                                             (filter-for-user groups))
              active? (= "active" (:presence user))
              ; turn the list of chans-or-grps-for-user into a list of chat sources
              chat-sources (set (map (comp chat-source chan-or-group-name)
                                     chans-or-grps-for-user))
              ; create a user model
              user-model (users/create-user
                           (:name user) active?
                           (assoc user :mention-name
                                  (str "<@" (:id user) ">")))]
          (if (empty? chat-sources)
            (users/add-user-without-room
              (:adapter (base-chat-source)) user-model)
            ;; for each chat source add a user individually
            (run! (fn [cs] (users/add-user cs user-model)) chat-sources))))
      users)))

;; lifecycle

(defn stop [conn]
  (when @conn
    (timbre/info "Closing" @conn)
    (slack/send-event (:dispatcher @conn) :close))
  (reset! conn nil))

(defn restart
  "conn is a reference to an atom.
   config is a map"
  [conn config]
  (reset! conn (slack/start (slack-config config)
                            :on-connect on-connect
                            :on-error on-error
                            :on-close (partial on-close conn config)
                            :presence_change on-presence-change
                            :channel_joined on-channel-joined
                            :group_joined on-channel-joined
                            :channel_left on-channel-left
                            :group_left on-channel-left
                            :manual_presence_change on-manual-presence-change
                            :message (partial on-message conn config)
                            :hello on-hello))

  (info "Slack self is" (pr-str (self conn)))
  (reset-users-from-conn conn))

(defn start [adapter conn config]
  (stop conn)
  (binding [*adapter* adapter]
    (info "adapter" adapter "starting up with config" config)
    (restart conn config)))

(defn history
  "chan-id can be the ID of a:
   - channel
   - group
   - direct message
   Retrieve history from the correct corresponding API."
  [adapter chan-id]
  (let [c (slack-config (:config adapter))]
    (condp = (first chan-id)
      ;; direct message - lookup the user
      \D (im/history c chan-id)
      ;; channel
      \C (channels/history c chan-id)
      ;; group
      \G (groups/history c chan-id)
      (throw (ex-info "unknown entity type" chan-id)))))

(defn react [adapter emoji channel]
  (let [c (slack-config (:config adapter))
        conn (:conn adapter)
        yb-id (:id (self conn))
        hist (history adapter channel)
        non-yb-non-cmd (->> (:messages hist)
                            (filter #(not= yb-id (:user %)))
                            (filter #(not (s/starts-with? (:text %) "!"))))
        msg (first non-yb-non-cmd)
        ts (:ts msg)]
    (reactions/add c emoji {:channel channel :timestamp ts})))

;; adapter impl

(defrecord Slack [config conn]
  a/Adapter

  (a/uuid [_] (:name config))

  (a/platform-name [_] "Slack")

  (a/rooms [_] (rooms config))

  (a/send-paste [_ msg] (send-paste config msg))

  (a/send-msg [_ msg] (send-msg config msg))

  (a/join [_ room]
    (str
      "Slack bots such as myself can't join rooms on their own. Use /invite "
      "@yetibot from the channel you'd like me to join instead.✌️"))

  (a/leave [_ room]
    (str "Slack bots such as myself can't leave rooms on their own. Use /kick "
         "@yetibot from the channel you'd like me to leave instead. 👊"))

  (a/chat-source [_ room] (chat-source room))

  (a/stop [_] (stop conn))

  (a/start [adapter] (start adapter conn config)))

(defn make-slack
  [config]
  (->Slack config (atom nil)))
