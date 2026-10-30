(ns yetibot.core.adapters.discord
  (:require [clojure.spec.alpha :as spec]
            [clojure.core.async :as async]
            [clojure.core.cache :as cache]
            [clojure.string :as str]
            [discljord.messaging :as messaging]
            [discljord.connections :as discord-ws]
            [yetibot.core.models.users :as users]
            [discljord.events :as events]
            [taoensso.timbre :as timbre]
            [yetibot.core.adapters.adapter :as adapter]
            [yetibot.core.handler :as handler]
            [yetibot.core.webapp.routes.images :as images]
            [yetibot.core.chat :as chat])
  (:import [java.util Base64]))

(spec/def ::type #{"discord"})
(spec/def ::token string?)
(spec/def ::config (spec/keys :req-un [::type ::token]))

(defmulti handle-event
  (fn [event-type event-data _conn yetibot-user]
    event-type))

(defn- discord-user [user bot-id]
  (assoc (users/create-user (:username user) user)
         :yetibot? (= (:id user) bot-id)))

;; also has :message-reaction-remove
(defmethod handle-event :message-reaction-add
  [_ event-data conn yetibot-user]
  (let [{:keys [message-id channel-id message-author-id user-id]} event-data
        emoji-name (get-in event-data [:emoji :name])
        rest-conn (:rest @conn)
        bot-id (:id @yetibot-user)
        ;; The gateway's message-author-id is optional. Fetch only if needed;
        ;; known bot-message delete/ignore events require no message lookup.
        message (delay @(messaging/get-channel-message!
                          rest-conn channel-id message-id))
        author-id (or message-author-id (get-in @message [:author :id]))
        own-message? (= author-id bot-id)]
    (cond
      (and own-message? (= emoji-name "❌"))
      (messaging/delete-message! rest-conn channel-id message-id)

      own-message?
      (timbre/debug "We don't handle" emoji-name "from yetibot")

      :else
      (let [cs (assoc (chat/chat-source channel-id)
                      :guild-id (:guild-id event-data) :raw-event event-data)
            reactor (or (get-in event-data [:member :user])
                        @(messaging/get-user! rest-conn user-id))]
        (binding [chat/*target* channel-id]
          (handler/handle-raw
            cs (discord-user reactor bot-id) :react @yetibot-user
            {:reaction emoji-name :body (:content @message)
             :message-user (discord-user (:author @message) bot-id)}))))))


(def ^:private command-cache-size 1000)

(def ^:dynamic ^:private *command-output*
  "Collect adapter output until a command finishes, before replacing its replies."
  nil)

(def ^:dynamic ^:private *command-context*
  "Request-local live output ownership; never stored in the command cache."
  nil)

(defn- command? [event]
  (and (string? (:content event))
       (:cmd? (handler/->parsed-message-info (:content event)))))

(defn- bot-message? [event yetibot-user]
  (or (get-in event [:author :bot])
      (= (:id @yetibot-user) (get-in event [:author :id]))))

(defn- run-message! [event yetibot-user]
  (let [cs (assoc (chat/chat-source (:channel-id event)) :raw-event event)]
    (binding [chat/*target* (:channel-id event)]
      (handler/handle-raw cs
                         (discord-user (:author event) (:id @yetibot-user))
                         :message @yetibot-user {:body (:content event)}))))

(def ^:private live-delivery-timeout-ms 30000)
(def ^:dynamic ^:private *delivery-deadline* nil)

(defn- live-deadline []
  (+ (System/nanoTime) (* 1000000 live-delivery-timeout-ms)))

(defn- delivery-error []
  (ex-info "Discord live delivery failed" {}))

(defn- delivery! [dispatch]
  (if-not *delivery-deadline*
    @(dispatch)
    (try
      ;; A single monotonic deadline covers the whole batch, not each chunk.
      (let [remaining (- *delivery-deadline* (System/nanoTime))]
        (when-not (pos? remaining) (throw (delivery-error)))
        (let [result (dispatch)
              remaining (- *delivery-deadline* (System/nanoTime))
              timeout (Object.)]
          (when-not (and (pos? remaining)
                         (instance? clojure.lang.IBlockingDeref result))
            (throw (delivery-error)))
          (let [value (deref result
                             (long (Math/ceil (/ remaining 1000000.0)))
                             timeout)]
            (when (identical? timeout value) (throw (delivery-error)))
            value)))
      ;; No raw transport exception, response data, or content escapes here.
      (catch Exception _ (throw (delivery-error))))))

(declare replace-replies!)

(defn- register-command! [event conn revision]
  ;; This short metadata lock never waits on the per-command REST IO lock.
  (locking conn
    (let [key [(:channel-id event) (:id event)]
          previous (or (get-in @conn [:active-commands key])
                       (get-in @conn [:commands key]))
          owner {:revision (or (:revision previous) (atom nil))
                 :lock (or (:lock previous) (Object.))
                 :replies (or (:replies previous) (atom []))
                 :running (or (:running previous) (atom #{}))}
          entry (assoc (dissoc owner :running) :event event)]
      (reset! (:revision owner) revision)
      (swap! (:running owner) conj revision)
      (swap! conn
             (fn [state]
               (-> state
                   (assoc-in [:active-commands key] owner)
                   (update :commands
                           #(cache/miss
                              (or % (cache/lru-cache-factory
                                      {} :threshold command-cache-size))
                              key entry)))))
      [key owner entry])))

(defn- retire-command! [conn key owner revision]
  (locking conn
    (let [running (:running owner)]
      (swap! running disj revision)
      (when (and (empty? @running)
                 (identical? running
                             (get-in @conn [:active-commands key :running])))
        (swap! conn update :active-commands dissoc key)))))

(defn- run-command! [event conn yetibot-user]
  (let [revision (Object.)
        [key owner entry] (register-command! event conn revision)
        output (atom [])
        live? (atom false)]
    (try
      (let [completion
            (binding [*command-output* output
                      *command-context* {:conn conn :entry entry
                                         :running (:running owner)
                                         :revision revision :output output
                                         :live? live?}]
              (when (command? event) (run-message! event yetibot-user)))]
        (async/thread
          (try
            (when completion (async/<!! completion))
            (locking (:lock entry)
              (when (identical? revision @(:revision entry))
                (binding [*delivery-deadline* (when @live? (live-deadline))]
                  (replace-replies! conn (:replies entry) @output))))
            (catch Exception e
              (timbre/error e "Error replacing Discord command replies"))
            (finally (retire-command! conn key owner revision)))))
      (catch Throwable e
        (retire-command! conn key owner revision)
        (throw e)))))

(defmethod handle-event :message-create
  [_ event-data conn yetibot-user]
  (when-not (bot-message? event-data yetibot-user)
    (if (and (:id event-data) (command? event-data))
      (run-command! event-data conn yetibot-user)
      (run-message! event-data yetibot-user))))

(defmethod handle-event :message-update
  [_ event-data conn yetibot-user]
  ;; Discord also sends updates for link previews. Only content/attachment
  ;; changes should rerun commands; partial events retain the original context.
  (when (and (contains? event-data :content)
             (not (bot-message? event-data yetibot-user)))
    (let [previous (get-in @conn [:commands [(:channel-id event-data)
                                           (:id event-data)]])]
      (when (or previous (command? event-data))
        (let [original (or (:event previous)
                           @(messaging/get-channel-message!
                              (:rest @conn) (:channel-id event-data)
                              (:id event-data)))
              event (assoc (merge original event-data)
                           :author (merge (:author original) (:author event-data)))]
          (when (and (:id (:author event))
                     (not (bot-message? event yetibot-user))
                     (or (nil? previous)
                         (not= (select-keys original [:content :attachments])
                               (select-keys event [:content :attachments]))))
            (run-command! event conn yetibot-user)))))))

(defn start
  "start the discord connection"
  [{conn :conn
    config :config
    connected? :connected?
    bot-id :bot-id
    yetibot-user :yetibot-user :as adapter}]
  (timbre/debug "starting discord connection")

  (binding [chat/*adapter* adapter]
    (let [event-channel (async/chan 100)
          message-channel (discord-ws/connect-bot! (:token config) event-channel :intents #{:guilds :guild-messages :guild-message-reactions :direct-messages :direct-message-reactions})
          rest-connection (messaging/start-connection! (:token config))
          retcon {:event  event-channel
                  :message message-channel
                  :rest    rest-connection}]
      (reset! conn retcon)

      (timbre/debug (pr-str conn))

      (reset! connected? true)
      (reset! bot-id {:id @(messaging/get-current-user! rest-connection)})
      (reset! yetibot-user @(messaging/get-current-user! rest-connection))
      (events/message-pump! event-channel (fn [event-type event-data] (handle-event event-type event-data conn yetibot-user))))))

(defn- channels [a]
  (let [guild-channels (messaging/get-guild-channels!)]
    (timbre/debug "Guild Channels: " (pr-str guild-channels))
    (guild-channels)))

(defn- generated-image-info [msg]
  (when-let [[url id ext] (re-find #"https?://\S+?/generated-images/([^./\s]+)\.(png|gif|mp4)" msg)]
    {:url url :id id :ext ext}))

(def discord-max-message-length 2000)

(defn- chunk-message [msg]
  (let [chunks (partition-all discord-max-message-length msg)]
    (map str/join chunks)))

(defn- message-parts [msg]
  (let [{:keys [url id ext]} (generated-image-info msg)
        data (:data (get @images/image-store id))]
    (if data
      (let [clean-msg (str/replace msg url "")]
        [(cond-> {:image data :filename (str id "." ext)}
           (not (str/blank? clean-msg)) (assoc :content clean-msg))])
      (mapv #(hash-map :content %) (chunk-message msg)))))

(defn- create-message! [conn target {:keys [image filename] :as part}]
  (let [options (cond-> (select-keys part [:content :allowed-mentions])
                  image (assoc :stream
                               {:content (java.io.ByteArrayInputStream.
                                           (.decode (Base64/getDecoder)
                                                    ^String image))
                                :filename filename}))]
    (delivery! #(apply messaging/create-message! (:rest @conn) target
                       (mapcat identity options)))))

(defn- send-msg [{:keys [conn]} msg]
  (try
    (let [parts (mapv #(assoc % :target chat/*target*) (message-parts msg))]
      (if *command-output*
        (do (swap! *command-output* into parts) nil)
        (last (mapv #(create-message! conn (:target %) %) parts))))
    (catch Exception e
      (timbre/error "Error sending Discord message:" e))))

(defn- delete-reply! [conn {:keys [id target]}]
  (delivery! #(messaging/delete-message! (:rest @conn) target id)))

(defn- replace-reply-at!
  [conn replies index {:keys [target image content allowed-mentions] :as part}]
  (let [old (get @replies index)
        ;; discljord 1.3.1 cannot edit attachment uploads. Upload a replacement
        ;; first, then remove the old image; ordinary text keeps its message ID.
        edit? (and old (= target (:target old))
                   (not image) (not (:image? old)))
        ;; Unlike create-message!, discljord's edit options retain JSON keys.
        options (cond-> {:content content}
                  allowed-mentions (assoc :allowed_mentions allowed-mentions))
        edited (when edit?
                 (delivery! #(apply messaging/edit-message!
                                    (:rest @conn) target (:id old)
                                    (mapcat identity options))))
        missing? (= 10008 (or (:code edited) (:code (ex-data edited))))
        message (if (and edit? (not missing?))
                  edited
                  (create-message! conn target part))]
    (when-not (:id message)
      (throw (ex-info "Discord did not return a replacement message ID" {})))
    (swap! replies
           (fn [records]
             ;; Buffered normal output may precede the first live part. Reserve
             ;; those indices without sending the buffered prefix early.
             (assoc (into records
                          (repeat (max 0 (- index (count records))) nil))
                    index {:id (:id message) :target target
                           :image? (boolean image)})))
    (when (and old (not edit?)) (delete-reply! conn old))))

(defn- replace-replies! [conn replies parts]
  (doseq [[index part] (map-indexed vector parts)]
    ;; Failed/unknown and unattempted live slots are never automatically sent.
    ;; Keep their indices so independently buffered later output is not lost.
    (when (or (nil? (:delivered? part)) @(:delivered? part))
      (replace-reply-at! conn replies index part)))
  (doseq [old (keep identity (drop (count parts) @replies))]
    (delete-reply! conn old))
  (swap! replies #(vec (take (count parts) %))))

(defn publish-command-output!
  "Immediately publish pre-split text chunks to a Discord channel/thread target.

  chunks must be a vector of at most 30 strings, each at most 2000 characters;
  invalid chunks throw a static IllegalArgumentException before any IO.
  Returns :published after delivery, :stale without IO if this request's saved
  revision no longer owns the command, or :unavailable without IO outside a
  command context. Never fall back on :stale. Delivery errors propagate as
  static exceptions. Live batches and terminal replay each share a 30s deadline.
  Failed/unknown and unattempted live slots are not automatically resent;
  confirmed IDs and independently buffered output survive partial failure.
  A timed-out dispatched create can still arrive later; it is not cancellable.

  Live parts suppress mentions on create and replay and participate in command
  edit/reply tracking. Callers own aggregate per-request chunk/byte budgets.
  The dynamic request context propagates through bound async work, not caches."
  [target chunks]
  (when-not (and (vector? chunks)
                 (<= (count chunks) 30)
                 (every? #(and (string? %)
                               (<= (count %) discord-max-message-length))
                         chunks))
    (throw (IllegalArgumentException. "Invalid Discord command output chunks")))
  (if-let [{:keys [conn entry running revision output live?]} *command-context*]
    (locking (:lock entry)
      (if-not (and (identical? revision @(:revision entry))
                   (contains? @running revision))
        :stale
        (binding [*delivery-deadline* (live-deadline)]
          (let [parts (mapv #(hash-map :target target :content %
                                      :allowed-mentions {:parse []}
                                      :delivered? (atom false)) chunks)
                [before _] (swap-vals! output into parts)
                start (count before)]
            (reset! live? true)
            (try
              (doseq [[offset part] (map-indexed vector parts)]
                (replace-reply-at! conn (:replies entry) (+ start offset) part)
                (reset! (:delivered? part) true))
              :published
              (catch Exception _ (throw (delivery-error))))))))
    :unavailable))

(defn stop
  "stop the discord connection"
  [{:keys [conn] :as adapter}]
  (timbre/debug "Closing Discord" (adapter/uuid adapter))
  (messaging/stop-connection! (:message @conn))
  (async/close!           (:event @conn)))

(defn react
  "reacts to messages in a given channel for a specific adapter that are
   non-YB commands and not from the YB user"
  [adapter emoji channel-id]
  (timbre/debug "react" {:adapter adapter :emoji emoji :channel channel-id})
  (let [conn (:conn adapter)
        rest-conn (:rest @conn)
        yb-id (:id (:yetibot-user adapter))
        messages @(messaging/get-channel-messages! rest-conn channel-id :limit 10)
        non-yb-non-cmd (->> messages
                            (filter #(not= yb-id (-> % :author :id)))
                            (filter #(not (str/starts-with? (:content %) "!"))))
        msg (first non-yb-non-cmd)
        message-id (:id msg)]
    (timbre/debug "react with"
                  {:msg msg :emoji emoji :channel channel-id :message-id message-id})
    (when message-id
      @(messaging/create-reaction! rest-conn channel-id message-id emoji))))

(defrecord Discord
           [config
            bot-id
            conn
            connected?
            connection-last-active-timestamp
            connection-latency
            should-ping?
            yetibot-user]

  adapter/Adapter

  (a/uuid [_] (:name config))

  (a/platform-name [_] "Discord")

  (a/channels [a] (channels a))

  (a/send-paste [a msg] (send-msg a msg))

  (a/send-msg [a msg] (send-msg a msg))

  (a/join [_ channel]
    (str
     "Discord bots such as myself can't join channels on their own. Use "
     "/invite from the channel you'd like me to join instead.✌️"))

  (a/leave [_ channel]
    (str
     "Discord bots such as myself can't leave channels on their own. Use "
     "/kick from the channel you'd like me to leave instead. 👊"))

  (a/chat-source [_ channel] (chat/chat-source channel))

  (a/stop [adapter] (stop adapter))

  (a/connected? [{:keys [connected?]}]
    @connected?)

  (a/connection-last-active-timestamp [_]
    @connection-last-active-timestamp)

  (a/connection-latency [_]
    @connection-latency)

  (a/start [adapter]
    (start adapter)))

(defn make-discord
  [config]
  (map->Discord
   {:config config
    :bot-id (atom nil)
    :conn (atom nil)
    :connected? (atom false)
    :connection-latency (atom nil)
    :connection-last-active-timestamp (atom nil)
    :yetibot-user (atom nil)
    :should-ping? (atom false)}))
