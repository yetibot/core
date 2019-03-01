(ns yetibot.core.adapters.slack
  (:require
    [clojure.core.async :as async]
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
     [conversations :as conversations]
     [rtm :as rtm]]
    [slack-rtm.core :as slack]
    [taoensso.timbre :as timbre :refer [color-str debug info warn error]]
    [yetibot.core.handler :refer [handle-raw]]
    [yetibot.core.chat :refer [base-chat-source chat-source
                               chat-data-structure *thread-ts* *target* *adapter*]]
    [yetibot.core.util :as utl]))

(def channel-cache-ttl 60000)

(def slack-ping-pong-interval-ms
  "How often to send Slack a ping event to ensure the connection is active"
  10000)

(def slack-ping-pong-timeout-ms
  "How long to wait for a `pong` after sending a `ping` before marking the
   connection as inactive and attempting to restart it"
  5000)

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

(defn find-yetibot-user
  [conn cs]
  (let [yetibot-uid (:id (self conn))]
    (users/get-user cs yetibot-uid)))

;;;;

(defn chan-or-group-name
  "Takes either a channel or a group and returns its name as a String.
   If it's a public channel, # is prepended.
   If it's a group, just return the name."
  [chan-or-grp]
  (str (when (:is_channel chan-or-grp) "#")
       (:name chan-or-grp)))

(defn channels
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
     :attachments [{:pretext ""
                    :text msg}]}))

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

(defn on-channel-join [{:keys [channel] :as e} conn config]
  (let [[chan-name entity] (entity-with-name-by-id config {:channel channel})
        cs (chat-source chan-name)
        user-model (users/get-user cs (:user e))
        yetibot-user (find-yetibot-user conn cs)]
    (binding [*target* channel]
      (timbre/info "channel join" (color-str :blue (with-out-str (pprint cs))))
      (handle-raw cs user-model :enter yetibot-user {}))))

(defn on-channel-leave [{:keys [channel] :as e} conn config]
  (let [[chan-name entity] (entity-with-name-by-id config {:channel channel})
        cs (chat-source chan-name)
        user-model (users/get-user cs (:user e))
        yetibot-user (find-yetibot-user conn cs)]
    (binding [*target* channel]
      (timbre/info "channel leave" e)
      (handle-raw cs user-model :leave yetibot-user {}))))

(defn on-message-changed [{:keys [channel] {:keys [user text]} :message}
                          conn config]
  (timbre/info "message changed")
  ;; ignore message changed events from Yetibot - it's probably just Slack
  ;; unfurling stuff and we need to ignore it or it will result in double
  ;; history
  (let [[chan-name entity] (entity-with-name-by-id config {:channel channel
                                                           :user user})
        cs (chat-source chan-name)
        yetibot-user (find-yetibot-user conn cs)
        yetibot-uid (:id yetibot-user)
        yetibot? (= yetibot-uid user)
        user-model (assoc (users/get-user cs user)
                          :yetibot? yetibot?)]
    (if yetibot?
      (info "ignoring message changed event from Yetibot user" yetibot-uid)
      (binding [*target* channel]
        (handle-raw cs
                    user-model
                    :message
                    yetibot-user
                    {:body (unencode-message text)})))))

(defn on-message [{:keys [conn config] :as adapter} {:keys [subtype] :as event}]
  ;; allow bot_message events to be treated as normal messages
  (if (and (not= "bot_message" subtype) subtype)
    (do
      (info "event subtype" subtype)
      ; handle the subtype
      (condp = subtype
        "channel_join" (on-channel-join event conn config)
        "group_join" (on-channel-join event conn config)
        "channel_leave" (on-channel-leave event conn config)
        "group_leave" (on-channel-leave event conn config)
        "message_changed" (on-message-changed event conn config)
        ; do nothing if we don't understand
        (info "Don't know how to handle message subtype" subtype)))
    (let [{chan-id :channel thread-ts :thread_ts} event
          [chan-name entity] (entity-with-name-by-id config event)
          {:keys [is_channel]} entity
          ;; _ (info "channel entity:" (pr-str entity))
          ;; _ (info "event entity:" (color-str :red (pr-str event)))
          ;; TODO we probably need to switch to chan-id when building the
          ;; Slack chat-source since they are moving away from being able to
          ;; use user names as IDs
          cs (assoc (chat-source chan-name)
                    :is-private (not (boolean is_channel)))
          ;; _ (info "chat source" (color-str :green (pr-str cs)))
          yetibot-user (find-yetibot-user conn cs)
          yetibot-uid (:id yetibot-user)
          yetibot? (= yetibot-uid (:user event))
          user-model (assoc (users/get-user cs (:user event))
                            :yetibot? yetibot?)
          body (if (s/blank? (:text event))
                 ;; if text is blank attempt to read an attachment fallback
                 (->> event :attachments (map :text) (s/join \newline))
                 ;; else use the much more common `text` property
                 (:text event))]
      (binding [*thread-ts* thread-ts
                *target* chan-id]
        (handle-raw cs
                    user-model
                    :message
                    yetibot-user
                    {:body (unencode-message body)})))))

(defn on-reaction-added
  "https://api.slack.com/events/reaction_added"
  [{:keys [conn config] :as adapter}
   {:keys [user reaction item_user item] :as event}]
  (let [[chan-name entity] (entity-with-name-by-id config item)
        sc (slack-config config)
        cs (chat-source chan-name)
        yetibot-user (find-yetibot-user conn cs)
        yetibot-uid (:id yetibot-user)
        yetibot? (= yetibot-uid (:user event))
        user-model (assoc (users/get-user cs (:user event))
                          :yetibot? yetibot?)
        reaction-message-user (assoc (users/get-user cs item_user)
                                     :yetibot? (= yetibot-uid item_user))

        {[parent-message] :messages} (conversations/history
                                       sc (:channel item)
                                       {:latest (:ts item)
                                        :inclusive "true"
                                        :count "1"})

        parent-ts (:ts parent-message)

        ;; figure out if the user reacted to the top level parent of the thread
        ;; or a child
        is-parent? (= (:ts parent-message) (:ts item))

        child-ts (:ts item)

        child-message (when-not is-parent?
                        (->> (conversations/replies
                               sc (:channel item)
                               parent-ts
                               {:latest child-ts
                                :inclusive "true"
                                :limit "1"})
                             :messages
                             (filter (fn [{:keys [ts]}] (= ts child-ts)))
                             first
                             ))

        message (if is-parent? parent-message child-message)]
    ;; only support reactions on message types
    (when (= "message" (:type item))
      (binding [*target* (:channel item)
                *thread-ts* parent-ts]
        (handle-raw cs user-model :react yetibot-user
                    {:reaction (s/replace reaction "_" " ")
                     ;; body of the message reacted to
                     :body (:text message)
                     ;; user of the message that was reacted to
                     :message-user reaction-message-user})))))

(defn on-hello [event]
  (timbre/debug "Hello, you are connected to Slack" event))

(defn on-pong [{:keys [conn event connection-last-active-timestamp
                       connection-latency ping-time] :as adapter}
               event]
  (let [ts @ping-time
        now (System/currentTimeMillis)]
    (timbre/trace "Pong" (pr-str event))
    (reset! connection-last-active-timestamp now)
    (when ts (reset! connection-latency (- now ts)))))

(defn start-pinger!
  "Send a ping event to Slack to ensure the connection is active"
  [{:keys [conn should-ping? ping-time]}]
  (async/go-loop [n 0]
    (when @should-ping?
      (when-let [c @conn]
        (let [ts (System/currentTimeMillis)
              ping-event {:type :ping
                          :id n
                          :time ts}]
          (timbre/trace "Pinging Slack" (pr-str ping-event))
          (reset! ping-time ts)
          (slack/send-event (:dispatcher c) ping-event)
          (async/<!! (async/timeout slack-ping-pong-interval-ms))
          (recur (inc n)))))))

(defn stop-pinger! [{:keys [should-ping?]}]
  (reset! should-ping? false))

(defn on-connect [{:keys [conn connected? should-ping?] :as adapter} e]
  (reset! should-ping? true)
  (reset! connected? true)
  (start-pinger! adapter))

(declare restart)

;; See https://developer.mozilla.org/en-US/docs/Web/API/CloseEvent for the full
;; list of status codes on a close event
(def status-normal-close 1000)

(defn on-close [{:keys [conn config connected?] :as adapter}
                {:keys [status-code] :as status}]
  (reset! connected? false)
  (timbre/info "close" (:name config) status)
  (when (not= status-normal-close status-code)
    (try-try-again
      {:decay 1.1 :sleep 5000 :tries 500}
      (fn []
        (timbre/info "attempt no." rb/*try* " to rereconnect to slack")
        (when rb/*error* (timbre/info "previous attempt errored:" rb/*error*))
        (when rb/*last-try* (timbre/warn "this is the last attempt"))
        (restart adapter)))))

(defn on-error [exception]
  (timbre/error "error in slack" exception))

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

(defn on-channel-joined
  "Fires when yetibot gets invited and joins a channel or group"
  [e]
  (timbre/debug "channel joined" e)
  (let [c (:channel e)
        {:keys [uuid room] :as cs} (chat-source (:id c))
        user-ids (:members c)]
    (timbre/debug "adding chat source" cs "for users" user-ids)
    (dorun (map #(users/add-chat-source-to-user cs %) user-ids))))

(defn on-channel-left
  "Fires when yetibot gets kicked from a channel or group"
  [e]
  (timbre/debug "channel left" e)
  (let [c (:channel e)
        {:keys [uuid room] :as cs} (chat-source c)
        users-in-chan (users/get-users cs)]
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
            (users/add-user-without-channel
              (:adapter (base-chat-source)) user-model)
            ;; for each chat source add a user individually
            (run! (fn [cs] (users/add-user cs user-model)) chat-sources))))
      users)))

;; lifecycle

(defn stop [{:keys [should-ping? conn] :as adapter}]
  (timbre/info "Stop Slack" (pr-str should-ping? conn))
  (reset! should-ping? false)
  (when @conn
    (timbre/info "Closing" @conn)
    (slack/send-event (:dispatcher @conn) :close))
  (reset! conn nil))

(defn restart
  "conn is a reference to an atom.
   config is a map"
  [{:keys [conn config connected?] :as adapter}]
  (reset! conn (slack/start (slack-config config)
                            :on-connect (partial on-connect adapter)
                            :on-error on-error
                            :on-close (partial on-close adapter)
                            :presence_change on-presence-change
                            :channel_joined on-channel-joined
                            :group_joined on-channel-joined
                            :channel_left on-channel-left
                            :group_left on-channel-left
                            :manual_presence_change on-manual-presence-change
                            :message (partial on-message adapter)
                            :reaction_added (partial on-reaction-added adapter)
                            :pong (partial on-pong adapter)
                            :hello on-hello))
  (info "Slack (re)connected as Yetibot with id" (:id (self conn)))
  (reset-users-from-conn conn))

(defn start [adapter conn config connected?]
  (stop adapter)
  (binding [*adapter* adapter]
    (info "adapter" adapter "starting up with config" config)
    (restart adapter)))

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
    (debug "react with"
           (pr-str {:emoji emoji :channel channel :timestamp ts}))
    (reactions/add c emoji {:channel channel :timestamp ts})))

;; adapter impl

(defrecord Slack [config conn connected? connection-last-active-timestamp
                  ping-time connection-latency should-ping?]
  a/Adapter

  (a/uuid [_] (:name config))

  (a/platform-name [_] "Slack")

  (a/channels [_] (channels config))

  (a/send-paste [_ msg] (send-paste config msg))

  (a/send-msg [_ msg] (send-msg config msg))

  (a/join [_ channel]
    (str
      "Slack bots such as myself can't join channels on their own. Use /invite "
      "@yetibot from the channel you'd like me to join instead.✌️"))

  (a/leave [_ channel]
    (str "Slack bots such as myself can't leave channels on their own. Use /kick "
         "@yetibot from the channel you'd like me to leave instead. 👊"))

  (a/chat-source [_ channel] (chat-source channel))

  (a/stop [adapter] (stop adapter))

  (a/connected? [{:keys [connected?
                         connection-last-active-timestamp]}]
    (let [now (System/currentTimeMillis)
          time-since-last-active (- now @connection-last-active-timestamp)
          surpassed-timeout? (> time-since-last-active
                                (+ slack-ping-pong-timeout-ms
                                   slack-ping-pong-interval-ms))]
      (and @connected?
           (not surpassed-timeout?))))


  (a/connection-last-active-timestamp [_] @connection-last-active-timestamp)

  (a/connection-latency [_] @connection-latency)

  (a/start [adapter] (start adapter conn config connected?)))

(defn make-slack
  [config]
  (map->Slack
    {:config config
     :conn (atom nil)
     :connected? (atom false)
     :connection-latency (atom nil)
     :connection-last-active-timestamp (atom nil)
     :ping-time (atom nil)
     :should-ping? (atom false)}))
