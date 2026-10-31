(ns yetibot.core.adapters.discord
  (:require [clojure.spec.alpha :as spec]
            [clojure.core.async :as async]
            [clojure.core.cache :as cache]
            [clojure.string :as str]
            [discljord.messaging :as messaging]
            [discljord.connections :as discord-ws]
            [yetibot.core.models.users :as users]
            [yetibot.core.models.help :as help]
            [yetibot.core.util.command :as command]
            [yetibot.core.adapters.discord-components :as components]
            [discljord.events :as events]
            [taoensso.timbre :as timbre]
            [yetibot.core.adapters.adapter :as adapter]
            [yetibot.core.handler :as handler]
            [yetibot.core.webapp.routes.images :as images]
            [yetibot.core.chat :as chat])
  (:import [java.util Base64]
           [java.util.concurrent ScheduledThreadPoolExecutor ThreadFactory
            TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

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
            {:reaction emoji-name :body (components/message-text @message)
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

(defn- monotonic-time [] (System/nanoTime))

(defn- live-deadline []
  (+ (monotonic-time) (* 1000000 live-delivery-timeout-ms)))

(defn- delivery-error []
  (ex-info "Discord live delivery failed" {}))

(defn- delivery! [dispatch]
  (if-not *delivery-deadline*
    @(dispatch)
    (try
      ;; A single monotonic deadline covers the whole batch, not each chunk.
      (let [remaining (- *delivery-deadline* (monotonic-time))]
        (when-not (pos? remaining) (throw (delivery-error)))
        (let [result (dispatch)
              remaining (- *delivery-deadline* (monotonic-time))
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

(defn- acquire-command-lock! [^ReentrantLock lock]
  (if-not *delivery-deadline*
    (.lock lock)
    (try
      (let [remaining (- *delivery-deadline* (monotonic-time))]
        (when-not (and (pos? remaining)
                       (.tryLock lock remaining TimeUnit/NANOSECONDS))
          (throw (delivery-error))))
      (catch Exception _ (throw (delivery-error))))))

(defmacro ^:private with-command-lock [lock & body]
  `(let [lock# ~lock]
     (acquire-command-lock! lock#)
     (try ~@body (finally (.unlock ^ReentrantLock lock#)))))

(declare replace-replies! retire-status! cleanup-status!)

(defn- register-command! [event conn revision & [expected]]
  ;; This short metadata lock never waits on the per-command REST IO lock.
  (locking conn
    (let [key [(:channel-id event) (:id event)]
          previous (or (get-in @conn [:active-commands key])
                       (get-in @conn [:commands key]))
          owner {:revision (or (:revision previous) (atom nil))
                 :lock (or (:lock previous) (ReentrantLock.))
                 :replies (or (:replies previous) (atom []))
                 :running (or (:running previous) (atom #{}))
                 :details (atom nil)
                 :confirmation (atom nil)}
          entry (assoc (dissoc owner :running) :event event)]
      (when (or (nil? expected) (identical? expected @(:revision owner)))
        (when-let [status (get-in @conn [:command-statuses key])]
          (retire-status! {:conn conn :key key :status status :entry entry
                           :running (:running owner)
                           :revision @(:revision owner)}))
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
        [key owner entry]))))

(defn- retire-command! [conn key owner revision]
  (locking conn
    (let [running (:running owner)]
      (swap! running disj revision)
      (when (and (empty? @running)
                 (identical? running
                             (get-in @conn [:active-commands key :running])))
        (swap! conn update :active-commands dissoc key)))))

(defn- run-command! [event conn yetibot-user & [expected]]
  (let [revision (Object.)]
    (when-let [[key owner entry] (register-command! event conn revision expected)]
      (let [output (atom [])
            live? (atom false)
            context {:conn conn :entry entry :key key
                     :running (:running owner) :revision revision
                     :output output :live? live? :status (atom {})}]
        (try
          (let [completion
                (binding [*command-output* output
                          *command-context* context]
                  (when (command? event) (run-message! event yetibot-user)))]
            (async/thread
              (try
                (when completion (async/<!! completion))
                (binding [*delivery-deadline* (when @live? (live-deadline))]
                  (locking (:status context)
                    ;; Reserve cleanup before retirement exposes inactive status.
                    (swap! (:status context) assoc :terminal-pending? true)
                    (retire-status! context))
                  (try
                    (with-command-lock (:lock entry)
                      (try
                        (when (identical? revision @(:revision entry))
                          (replace-replies! conn (:replies entry) @output))
                        (finally (cleanup-status! context))))
                    (finally
                      (locking (:status context)
                        (swap! (:status context) dissoc :terminal-pending?)))))
                (catch Exception e
                  (timbre/error e "Error replacing Discord command replies"))
                (finally (retire-command! conn key owner revision)))))
          (catch Throwable e
            (retire-status! context)
            (retire-command! conn key owner revision)
            (throw e)))))))

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

(defn- interaction-reply! [conn event text]
  (messaging/create-interaction-response!
    (:rest @conn) (:id event) (:token event) 4
    :data (components/private-payload text)))

(defn- rerun! [conn event yetibot-user entry expected key]
  (let [reserved? (locking conn
                    (when-not (or (contains? (:reruns @conn) key)
                                  (seq @(get-in @conn [:active-commands key :running]
                                                (atom #{}))))
                      (swap! conn update :reruns (fnil conj #{}) key)
                      true))]
    (if-not reserved?
      (interaction-reply! conn event "This command is already running.")
      (try
        ;; ACK before execution; an edit received during the ACK supersedes
        ;; this click atomically at registration. No token enters the cache.
        (let [ack (messaging/create-interaction-response!
                    (:rest @conn) (:id event) (:token event) 6)]
          (when (= true (deref ack 2500 false))
            (when-let [completion (run-command! (:event entry) conn yetibot-user expected)]
              (async/<!! completion))))
        (finally (swap! conn update :reruns disj key))))))

(defmethod handle-event :interaction-create
  [_ event conn yetibot-user]
  (when-let [{:keys [action key confirmation]}
             (components/parse-button (get-in event [:data :custom-id]))]
    ;; Claim an interaction once before dispatch. Never retain its token.
    (let [fresh? (locking conn
                   (let [seen (or (:interactions @conn)
                                  (cache/lru-cache-factory {} :threshold 1000))]
                     (when-not (contains? seen (:id event))
                       (swap! conn assoc :interactions
                              (cache/miss seen (:id event) true))
                       true)))]
      (when fresh?
        (async/thread
          (try
            (let [entry (get-in @conn [:commands key])
                  source (:event entry)
                  pending (when entry @(:confirmation entry))
                  confirm? (= action "confirm")
                  expected (if confirm? (:revision pending)
                             (when entry @(:revision entry)))
                  actor (or (get-in event [:member :user]) (:user event))
                  own-message? (= (:id @yetibot-user)
                                  (get-in event [:message :author :id]))
                  known? (and entry own-message?
                              (or (not confirm?)
                                  (and (= confirmation (:token pending))
                                       (identical? expected @(:revision entry))
                                       (pos? (bit-and 64 (get-in event [:message :flags] 0)))))
                              (some #(and (= (:channel-id event) (:target %))
                                          (= (if confirm? (:reply-id pending)
                                               (get-in event [:message :id]))
                                             (:id %)))
                                    @(:replies entry)))]
              (cond
                (not known?)
                (interaction-reply! conn event
                  "This command's buttons have expired. Please send the command again.")

                (= action "help")
                (let [body (second (command/extract-command (:content source)))
                      topic (or (some->> body (re-find #"^\s*([^\s|`]+)") second) "help")
                      docs (or (help/get-docs-for topic) (help/fuzzy-get-docs-for topic))]
                  (interaction-reply! conn event
                    (str "**Help · " topic "**\n"
                         (if (seq docs) (str/join "\n\n" (sort docs))
                           "No help found for this command."))))

                (= action "details")
                (interaction-reply! conn event
                  (str "**Tool details**\n"
                       (or @(:details entry) "No tool details are available.")))

                (not= (:id actor) (get-in source [:author :id]))
                (interaction-reply! conn event
                  "Only the person who requested this command can run it again.")

                (= action "again")
                (let [token (str (java.util.UUID/randomUUID))]
                  (reset! (:confirmation entry)
                          {:token token :revision expected
                           :reply-id (get-in event [:message :id])})
                  (messaging/create-interaction-response!
                    (:rest @conn) (:id event) (:token event) 4
                    :data (components/confirmation-payload key token)))

                :else
                ;; Re-enter the normal handler as the original requester, not
                ;; a privileged bot-authored command.
                (rerun! conn event yetibot-user entry expected key)))
            (catch Exception _
              (timbre/warn "Discord command interaction failed"))))))))

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
          status-connection (try
                              (messaging/start-connection! (:token config))
                              (catch Exception _ nil))
          retcon {:event event-channel
                  :message message-channel
                  :rest rest-connection
                  :status-rest status-connection}]
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

(defn- message-parts [msg]
  (let [{:keys [url id ext]} (generated-image-info msg)
        data (:data (get @images/image-store id))]
    (if data
      (let [caption (str/replace msg url "")
            image {:image data :filename (str id "." ext)}]
        (if (<= (count caption) discord-max-message-length)
          [(cond-> image (not (str/blank? caption)) (assoc :content caption))]
          (into [image] (components/text-parts caption))))
      (components/text-parts msg))))

(defn- command-part [part]
  (if-let [{:keys [key entry]} *command-context*]
    (assoc part :command-key key :details-state (:details entry))
    part))

(defn- reply-options [part]
  (components/payload
    (cond-> part
      (:details-state part) (assoc :details @(:details-state part)))))

(defn- create-message! [conn target {:keys [image file filename] :as part}]
  (let [options (cond-> (reply-options part)
                  (or image file)
                  (assoc :stream
                         {:content (java.io.ByteArrayInputStream.
                                     (if image
                                       (.decode (Base64/getDecoder) ^String image)
                                       (.getBytes ^String file java.nio.charset.StandardCharsets/UTF_8)))
                          :filename filename}))]
    (delivery! #(apply messaging/create-message! (:rest @conn) target
                       (mapcat identity options)))))

(defn- send-msg [{:keys [conn]} msg]
  (try
    (let [parts (mapv #(command-part (assoc % :target chat/*target*))
                      (message-parts msg))]
      (if *command-output*
        (do (swap! *command-output* into parts) nil)
        (last (mapv #(create-message! conn (:target %) %) parts))))
    (catch Exception e
      (timbre/error "Error sending Discord message:" e))))

(defn- delete-reply! [conn {:keys [id target]}]
  (delivery! #(messaging/delete-message! (:rest @conn) target id)))

(defn- replace-reply-at!
  [conn replies index {:keys [target image file] :as part}]
  (let [old (get @replies index)
        ;; discljord 1.3.1 cannot edit attachment uploads. Upload a replacement
        ;; first, then remove the old image; ordinary text keeps its message ID.
        edit? (and old (= target (:target old))
                   (not image) (not file) (not (:image? old)))
        ;; Unlike create-message!, discljord's edit options retain JSON keys.
        options (let [payload (reply-options part)]
                  (-> payload (dissoc :allowed-mentions)
                      (assoc :allowed_mentions (:allowed-mentions payload))))
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
                           :image? (boolean (or image file))})))
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

(defonce ^:private status-scheduler
  (delay
    (doto
      (ScheduledThreadPoolExecutor.
        1
        (reify ThreadFactory
          (newThread [_ task]
            (doto (Thread. nil task "discord-command-status" 0 false)
              (.setDaemon true)))))
      (.setRemoveOnCancelPolicy true))))

(defn- schedule-status! [tick]
  (let [task (.scheduleWithFixedDelay
               ^ScheduledThreadPoolExecutor @status-scheduler
               ^Runnable tick 100 100 TimeUnit/MILLISECONDS)]
    #(.cancel task false)))

(defn- owns-command? [{:keys [entry running revision]}]
  (and (identical? revision @(:revision entry))
       (contains? @running revision)))

(declare status-tick!)

(defn- cancel-status-job! [status]
  (when-let [cancel (:cancel @status)]
    (swap! status dissoc :cancel)
    (try (cancel) (catch Exception _ nil))))

(defn- ensure-status-job! [{:keys [status entry] :as context}]
  (when-not (:cancel @status)
    (let [context (assoc (select-keys context
                                     [:conn :key :running :revision :status])
                         :entry (select-keys entry
                                             [:revision :lock :replies]))]
      (try
        (swap! status assoc :cancel
               (schedule-status! #(status-tick! context)))
        (catch Exception _ (swap! status assoc :failed? true))))))

(defn- retire-status! [{:keys [conn key status] :as context}]
  (locking status
    (when (:active? @status) (cancel-status-job! status))
    (swap! status assoc :started? true :active? false
           :text nil :sent-text nil)
    (swap! conn update :command-statuses
           (fn [records]
             (if (identical? status (get records key))
               (dissoc records key)
               records)))
    (when (and (:status-rest @conn)
               (:id @status) (not (:cleanup-attempted? @status)))
      (when-not (:cleanup-deadline @status)
        (swap! status assoc :cleanup-deadline
               (or *delivery-deadline* (live-deadline))))
      (ensure-status-job! context))))

(defn- safe-status-id? [{:keys [conn entry key status]}]
  (let [{:keys [id target]} @status
        current (get-in @conn [:command-statuses key])
        newer (when (and current (not (identical? current status))) @current)
        address [target id]]
    (and id
         (not= address key)
         (not= address [(:target newer) (:id newer)])
         (not-any? #(= address [(:target %) (:id %)]) @(:replies entry)))))

(defn- dispatch-status-cleanup! [{:keys [conn status] :as context}]
  (when (and (:id @status) (not (:cleanup-attempted? @status)))
    (swap! status assoc :cleanup-attempted? true)
    (when (safe-status-id? context)
      (let [{:keys [target id]} @status]
        (try
          (swap! status assoc :cleanup
                 (when-let [rest (:status-rest @conn)]
                   (messaging/delete-message! rest target id)))
          (catch Exception _ nil)))))
  (cancel-status-job! status))

(defn- cleanup-status! [{:keys [status] :as context}]
  (try
    (let [receipt
          (locking status
            (if (and *delivery-deadline*
                     (pos? (- *delivery-deadline* (monotonic-time))))
              (do
                (dispatch-status-cleanup! context)
                (:cleanup @status))
              (when (or (nil? (:cleanup-deadline @status))
                        (>= (monotonic-time) (:cleanup-deadline @status)))
                (cancel-status-job! status)
                nil)))]
      (when receipt (delivery! (constantly receipt))))
    (catch Exception _ nil)))

(defn- status-components [text]
  [{:type 17 :components [{:type 10 :content text}]}])

(defn- status-receipt! [context create? text value]
  (let [{:keys [status]} context]
    (locking status
      (let [{:keys [id target]} @status
            valid? (and (map? value) (not (:code value))
                        (string? (:id value))
                        (not (str/blank? (:id value)))
                        (or (nil? (:channel-id value))
                            (= target (:channel-id value)))
                        (or create? (= id (:id value))))]
        (swap! status assoc :pending? false)
        (if valid?
          (swap! status assoc :id (:id value)
                 :sent-text (when (:active? @status) text))
          (swap! status assoc :failed? true))
        (when (and create? valid?
                   (or (not (:active? @status))
                       (not (owns-command? context))))
          (swap! status assoc :cleanup-deadline (live-deadline))
          (retire-status! context))))))

(defn- dispatch-status! [{:keys [conn status] :as context} now]
  (let [{:keys [id target text]} @status]
    (swap! status assoc :pending? true :create-attempted? true
           :next-status (+ now 1000000000))
    (try
      (let [rest (:status-rest @conn)
            _ (when-not rest (throw (delivery-error)))
            options [:flags 32768 :components (status-components text)
                     :allowed_mentions {:parse [] :replied_user false}]
            receipt (if id
                      (apply messaging/edit-message! rest target id options)
                      (apply messaging/create-message! rest target options))]
        (async/take! receipt #(status-receipt! context (nil? id) text %)))
      (catch Exception _
        (swap! status assoc :failed? true :pending? false)))))

(defn- dispatch-typing! [{:keys [conn status]}]
  (when-not (:typing-pending? @status)
    (swap! status assoc :typing-pending? true)
    (try
      (async/take!
        (when-let [rest (:status-rest @conn)]
          (messaging/trigger-typing-indicator! rest (:target @status)))
        (fn [_] (swap! status assoc :typing-pending? false)))
      (catch Exception _ (swap! status assoc :typing-pending? false)))))

(defn- status-tick! [{:keys [entry status] :as context}]
  (try
    (when-not (owns-command? context) (retire-status! context))
    (locking status
      (when (and (not (:active? @status))
                 (:cleanup-deadline @status)
                 (>= (monotonic-time) (:cleanup-deadline @status)))
        (cancel-status-job! status)))
    (let [lock ^ReentrantLock (:lock entry)]
      (when (.tryLock lock)
        (try
          (locking status
            (let [now (monotonic-time)
                  {:keys [active? next-typing next-status pending? failed?
                          text sent-text id create-attempted?
                          cleanup-deadline terminal-pending?]} @status]
              (if (and active? (owns-command? context))
                (do
                  (when (>= now next-typing)
                    (swap! status assoc :next-typing (+ now 8000000000))
                    (when (and (:active? @status) (owns-command? context))
                      (dispatch-typing! context)))
                  (when (and (not pending?) (not failed?)
                             (or id (not create-attempted?))
                             (not= text sent-text) (>= now next-status)
                             (:active? @status) (owns-command? context))
                    (dispatch-status! context now)))
                (when (and cleanup-deadline (not terminal-pending?))
                  (if (< now cleanup-deadline)
                    (dispatch-status-cleanup! context)
                    (cancel-status-job! status))))))
          (finally (.unlock lock)))))
    (catch Exception _ (locking status (cancel-status-job! status)))))

(defn- validate-status-text! [text]
  ;; Conservative application bound, NOT an asserted Discord protocol maximum.
  (when-not (and (string? text) (not (str/blank? text))
                 (<= (.length ^String text) 1800))
    (throw (IllegalArgumentException. "Invalid Discord command status"))))

(defn start-command-status!
  "Queue one transient V2 working card and best-effort typing for this request.

  target is a nonblank channel/thread ID string. text is caller-sanitized,
  nonblank, at most 1800 UTF16 units; never supply drafts or raw tool data.
  Invalid input throws a static IllegalArgumentException before IO. The target
  is immutable. Returns :accepted (queued, NOT confirmed delivery), :inactive
  if already started/finished, :stale if revision ownership was lost, or
  :unavailable outside a command context or without the ancillary REST lane.
  There is no direct-send fallback.

  Status is separate from ordered answers. Core never resends unknown/failed
  status creates/edits or affects publish-command-output!. Snapshots coalesce
  at about 1Hz; typing is best effort about every 8s. Terminal completion
  automatically retires status.
  Async callbacks must convey the original request bindings (e.g. bound-fn),
  just as for publish-command-output!; deferred work rechecks ownership."
  [target text]
  (validate-status-text! text)
  (when-not (and (string? target) (not (str/blank? target)))
    (throw (IllegalArgumentException. "Invalid Discord command status")))
  (if-let [{:keys [conn key status live?] :as context} *command-context*]
    (locking conn
      (locking status
        (cond
          (not (owns-command? context)) :stale
          (:started? @status) :inactive
          (nil? (:status-rest @conn)) :unavailable
          :else
          (do
            (reset! live? true)
            (let [now (monotonic-time)]
              (reset! status {:started? true :active? true :target target
                              :text text :next-status now :next-typing now}))
            (swap! conn assoc-in [:command-statuses key] status)
            (ensure-status-job! context)
            :accepted))))
    :unavailable))

(defn update-command-status!
  "Queue the newest bounded sanitized text; unchanged text causes no edit.
  Same text validation/binding requirements as start-command-status!.
  Returns :accepted (not a delivery receipt), :inactive before start or after
  finish, :stale on ownership loss, or :unavailable outside command context."
  [text]
  (validate-status-text! text)
  (if-let [{:keys [status entry] :as context} *command-context*]
    (locking status
      (cond
        (not (owns-command? context)) :stale
        (not (:active? @status)) :inactive
        :else (do (swap! status assoc :text text)
                  (reset! (:details entry) text)
                  :accepted)))
    :unavailable))

(defn finish-command-status!
  "Stop future status/typing ticks and queue best-effort known-ID cleanup.
  Returns :retired, NOT confirmation of deletion; :stale on ownership loss or
  :unavailable outside command context. Idempotent, including before start.
  Terminal answer flush and cleanup share one 30s monotonic deadline, answers
  first. Unknown creates are never resent; late confirmed IDs get separately
  bounded cleanup. Dispatched REST cannot be cancelled by stopping ticks."
  []
  (if-let [context *command-context*]
    (do (retire-status! context)
        (if (owns-command? context) :retired :stale))
    :unavailable))

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
    (with-command-lock (:lock entry)
      (if-not (and (identical? revision @(:revision entry))
                   (contains? @running revision))
        :stale
        (binding [*delivery-deadline* (live-deadline)]
          (let [text (apply str chunks)
                raw-parts (if (> (count text) 6000)
                            (components/text-parts text)
                            (mapv #(hash-map :content %) chunks))
                parts (mapv #(command-part
                               (assoc % :target target
                                        :allowed-mentions {:parse []}
                                        :delivered? (atom false))) raw-parts)
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
  (let [rest (:status-rest @conn)]
    (swap! conn dissoc :status-rest)
    (doseq [status (vals (:command-statuses @conn))]
      (locking status
        (swap! status assoc :active? false :text nil :sent-text nil)
        (cancel-status-job! status)))
    (swap! conn assoc :command-statuses {})
    (when rest
      (try (messaging/stop-connection! rest) (catch Exception _ nil))))
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
                            (filter #(not (str/starts-with? (components/message-text %) "!"))))
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
