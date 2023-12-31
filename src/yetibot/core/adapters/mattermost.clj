(ns yetibot.core.adapters.mattermost
  "Yetibot integration with the Mattermost open source chat platform:
   https://mattermost.org/

   This adapter works by opening a websocket streaming connection to a
   configured mattermost instance, and immediately authenticating.

   Upon authentication, Mattermost will stream events over the ws as described
   in the docs: https://api.mattermost.com/#tag/WebSocket

   If the Mattermost instance is hosted on a platform like Heroku with short
   websocket timeouts, the socket may be disconnected upon which it will attempt
   to re-establish connection. If any messages are sent during that time between
   disconnect and reconnect, Yetibot will not record or process them.

   To prevent disconnects we send a ping every 5000 ms and listen for pong. The
   diff between ping and pong is the measured latency, which we record and
   expose on the web dashboard.

   NB: This could be improved, if desired, by recording the timestamps at
   disconnect and reconnect and use the Mattermost REST API to obtain any
   messages between."
  (:require
   [clojure.data.json :as json]
   [clojure.string :as string]
   [clojure.spec.alpha :as s]
   [clojure.core.async :as async]
   [jdk.nio.ByteBuffer :as bb]
   [java-http-clj.websocket :as websocket]
   [robert.bruce :refer [try-try-again] :as rb]
   [mattermost-clj.api.bots :as bots]
   [mattermost-clj.api.teams :as teams]
   [mattermost-clj.api.posts :as posts]
   [mattermost-clj.api.channels :as channels]
   [mattermost-clj.api.users :as users]
   [yetibot.core.models.users :as models.users]
   [mattermost-clj.core :as mattermost]
   [taoensso.timbre :as timbre :refer [color-str debug trace info warn error]]
   [lambdaisland.uri :refer [uri]]
   [yetibot.core.handler :refer [handle-raw]]
   [yetibot.core.adapters.adapter :as a]
   [yetibot.core.chat :refer [base-chat-source chat-source chat-data-structure
                              *thread-ts* *target* *adapter*]]))

(def mattermost-ping-interval-ms
  "How often to send Mattermost a ping event to ensure the connection is active"
  5000)

(def mattermost-public-channel-type
  "https://api.mattermost.com/#tag/channels"
  "O")

(def close-status
  "The status code to send Mattermost when intentionally closing the websocket"
  3337)

(comment
  (def buffer (bb/*allocate (Long/BYTES)))
  (bb/put-long buffer (System/currentTimeMillis))
  (bb/get-long buffer 0)
  (type (System/currentTimeMillis)))

(s/def ::type #{"mattermost"})
(s/def ::token string?)
(s/def ::host string?) ;; NB: could be improved by checking for uri
(s/def ::secure string?)
(s/def ::config (s/keys :req-un [::type
                                 ::host
                                 ::token]
                        :opt-un [::secure]))

(defn url
  "Return an HTTP or WebSocket URL from mattermost config"
  [{host :host
    secure :secure
    :as config}
   & {websocket? :websocket?
      path :path}]
  (let [secure? (= "true" secure)]
    (str
     (-> (uri (str "//" host))
         (assoc :scheme (str
                         (if websocket? "ws" "http")
                         (when secure? "s"))
                :path path)))))

(declare start)

(defn send-msg
  [{:keys [api-context] :as adapter} msg]
  posts/posts-post-with-http-info
  (mattermost/with-api-context
    api-context
    (posts/posts-post-with-http-info
     {:inline-object-42
      {:channel_id *target*
       :root_id *thread-ts*
       :message msg}})))

(defn private-channel?
  "Determine whether a channel is private or not, according to:
   https://api.mattermost.com/#tag/channels"
  [{channel-type :type :as channel}]
  (= channel-type "P"))

(defn on-posted
  [{:keys [api-context yetibot-user]
    :as  adapter} {{post :post} :data :as event}]
  (mattermost/with-api-context
    api-context
    (binding [*adapter* adapter]
      (let [{:keys [user_id
                    channel_id
                    parent_id
                    props
                    message]
             :as post} (json/read-str post :key-fn keyword)
            channel (channels/channels-channel-id-get channel_id)
            private? (private-channel? channel)
            cs (assoc (chat-source (:name channel))
                      :raw-event event
                      :is-private private?)
            bot? (= "true" (:from_bot props))
            mattermost-user (users/users-user-id-get user_id)
            user-model (models.users/create-user
                        (:username mattermost-user)
                        mattermost-user)
            body message]
        (if bot?
          (debug "ignoring bot post" (pr-str post))
          (binding [*target* (:id channel)
                    *thread-ts* parent_id]
            ;; (debug "posted" (pr-str post))
            (handle-raw
             cs
             user-model
             :message
             @yetibot-user
             {:body body
              ;; allow chatters (like obs) to optionally reply
              ;; in-thread by propagating the thread-ts
              :thread-ts parent_id})))))))

(defn on-reaction-added
  [{:keys [api-context yetibot-user] :as adapter}
   {{reaction-json :reaction} :data
    {channel_id :channel_id} :broadcast
    :as event}]
  (info "Mattermost reaction added" (pr-str event))
  (try
    (mattermost/with-api-context
      api-context
      (binding [*adapter* adapter]
        (let [{:keys [user_id
                      post_id
                      emoji_name]
               :as reaction} (json/read-str reaction-json :key-fn keyword)
              channel (channels/channels-channel-id-get channel_id)
              private? (private-channel? channel)
              cs (assoc (chat-source (:name channel))
                        :raw-event event
                        :is-private private?)
             ;; bot? (= "true" (:from_bot props))
              mattermost-user (users/users-user-id-get user_id)
              user-model (models.users/create-user
                          (:username mattermost-user)
                          mattermost-user)
             ;; this is the post that the user reacted to
              {body :message
               ;; we need the parent_id of the post so we can populate that as
               ;; thread-ts
               parent_id :parent_id
               :as post} (posts/posts-post-id-get post_id)]
          (binding [*target* (:id channel)
                    *thread-ts* parent_id]
           ;; (debug "posted" (pr-str post))
            (handle-raw
             cs
             user-model
             :react
             @yetibot-user
             {:reaction (string/replace emoji_name "_" " ")
              :body body
              :thread-ts parent_id}))

         ;; TODO figure out if a bot caused the reaction and ignore it if so
          #_(if bot?
              (debug "ignoring bot post" (pr-str post))))))

    (catch Exception e
      (info "Exception on-reaction-added" (pr-str e)))))

(defn on-error
  "Report errors for debugging"
  [adapter err]
  (info "Mattermost error" (pr-str err))
  ;; if an error happens try reconnecting
  (start adapter))

(defn on-close
  [{:keys [conn connected?] :as adapter} ws status reason]
  (reset! connected? false)
  (reset! conn nil)
  (info "Mattermost closed" status reason)
  (if (= status close-status)
    (info "Intentionally closed" close-status)
    (start adapter)))

(defn on-receive
  "If last? is false, then there is more to a message than has been delivered to
   the invocation."
  [{:as adapter} ws message-str last?]
  (let [{:keys [event]
         :as message} (json/read-str message-str :key-fn keyword)]
    ;; (debug "message" message)
    (condp = event
      "posted" (on-posted adapter message)
      "reaction_added" (on-reaction-added adapter message)
      (debug "Unhandled Mattermost event:" event))))

(defn start-pinger!
  "Start pinging the ws. This should be idempotent to handle reconnects
   (which produce new websockets)."
  [{:keys [should-ping? conn] :as adapter}]
  (reset! should-ping? true)
  (info "Start pinging..." (a/uuid adapter))
  (async/go-loop [n 0]
    (if (and @should-ping? @conn (not (.isInputClosed @conn)))
      (let [now (System/currentTimeMillis)
            ping-payload (bb/*wrap (.getBytes (str now)))]
        (debug "Pinging" (a/uuid adapter) {:ping-count n :now now})
        (.sendPing @conn ping-payload)
        (async/<!! (async/timeout mattermost-ping-interval-ms))
        (recur (inc n)))
      ;; NOTE: maybe we should auto-reconnect here if @should-ping? is still
      ;; true, which means it was supposed to keep pinging but the connection
      ;; was closed for some reason.
      (debug "exiting pinger go loop" {:should-ping? @should-ping?}))))

(defn stop-pinger! [{:keys [should-ping?]}]
  (reset! should-ping? false))

(defn on-pong
  "Mattermost will send back the payload we sent it in ping, which was the
   timestamp of the ping. We can compare this to current time to determine
   latency."
  [{:keys [connection-latency connection-last-active-timestamp] :as a} ws data]
  (try
    (let [now (System/currentTimeMillis)
          ping-time (-> data slurp read-string)
          latency (- now ping-time)]
      (debug "Pong received with latency of" latency)
      (reset! connection-last-active-timestamp now)
      (reset! connection-latency latency))
    (catch Exception e
      (warn "Error in pong" \newline e))))

(defn stop
  [{:keys [conn] :as a}]
  (stop-pinger! a)
  (if-let [c @conn]
    (try
      (info "Stopping Mattermost websocket")
      (websocket/close c close-status "Yetibot closed this socket")
      (catch Exception e
        (info "Error stopping socket" (pr-str e)))
      (finally
        (reset! conn nil)))
   (info "Mattermost websocket is already closed")))

(defn start
  "Connect (or re-connect) to the Mattermost websocket"
  [{{token :token :as config} :config
    should-ping? :should-ping?
    connected? :connected?
    yetibot-user :yetibot-user
    conn :conn
    api-context :api-context
    :as adapter}]
  (let [auth {:seq 1,
              :action "authentication_challenge",
              :data {:token token}}
        ws-url (url config
                    :websocket? true
                    :path "/api/v4/websocket")
        me (mattermost/with-api-context
             api-context
             (users/users-user-id-get "me"))]
    (reset! yetibot-user me)
    ;; clean up old connection, if present
    (stop adapter)
    ;; then create a new one with retry and backoff
    (try-try-again
     {:decay 1.1 :sleep 5000 :tries 500}
     (fn []
       (binding [*adapter* adapter]
         (when rb/*error*
           (warn "Error starting Mattermost, trying again"
                 (pr-str rb/*error*)))
         (info "Starting Mattermost websocket" ws-url)
         (websocket/build-websocket
          ws-url
          {:on-text (partial #'on-receive adapter)
           :on-binary (fn [ws byte-arr last?]
                        (info "Received bytes" (vec byte-arr)))
           :on-close (partial #'on-close adapter)
           :on-error (partial #'on-error adapter)
           :on-pong (partial #'on-pong adapter)
           :on-open (fn [ws]
                      (reset! conn ws)
                      (reset! connected? true)
                      (info "Mattermost is connected")
                      (websocket/send ws (json/write-str auth))
                      (info "Mattermost auth sent")
                      (start-pinger! adapter))})
         (info "Started" (a/uuid adapter)))))))

(defn channels
  [{:keys [api-context yetibot-user] :as adapter}]
  (mattermost/with-api-context
    api-context
    (let [teams (teams/teams-get)]
      (mapcat
       (fn [{team-name :name team-id :id}]
         (let [channels
               (channels/users-user-id-teams-team-id-channels-get
                (:id @yetibot-user) team-id)]
           (->> channels
                (filter #(= mattermost-public-channel-type
                            (:type %)))
                (map (fn [c]
                       (prn c)
                       (format "%s/%s"
                               team-name
                               (:name c)))))))
       teams))))

(defrecord Mattermost
           [config
            conn
            connected?
            connection-last-active-timestamp
            connection-latency
            should-ping?
            yetibot-user
            api-context]

  a/Adapter

  (a/uuid [_] (:name config))

  (a/platform-name [_] "Mattermost")

  (a/channels [a] (channels a))

  (a/send-paste [a msg] (send-msg a msg))

  (a/send-msg [a msg] (send-msg a msg))

  (a/join [_ channel]
    (str
     "Mattermost bots such as myself can't join channels on their own. Use "
     "/invite from the channel you'd like me to join instead.✌️"))

  (a/leave [_ channel]
    (str
     "Mattermost bots such as myself can't leave channels on their own. Use "
     "/kick from the channel you'd like me to leave instead. 👊"))

  (a/chat-source [_ channel] (chat-source channel))

  (a/stop [adapter] (stop adapter))

  (a/connected? [{:keys [connected?]}]
    @connected?)

  (a/connection-last-active-timestamp [_]
    @connection-last-active-timestamp)

  (a/connection-latency [_]
    @connection-latency)

  (a/start [adapter]
    (start adapter)))

(defn make-mattermost
  [config]
  (map->Mattermost
   {:config config
    :api-context {:debug false
                  :base-url (url config :path "/api/v4")
                  :auths {"api_key" (str "Bearer " (:token config))}}
    :conn (atom nil)
    :connected? (atom false)
    :connection-latency (atom nil)
    :connection-last-active-timestamp (atom nil)
    :yetibot-user (atom nil)
    :should-ping? (atom false)}))
