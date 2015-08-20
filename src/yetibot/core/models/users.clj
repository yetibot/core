(ns yetibot.core.models.users
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [config-for-ns]]
    [clj-time.core :refer [now]]))

(def config {:active-threshold-milliseconds (* 15 60 1000)})

(defonce ^{:private true
          :doc
  "key: {:adapter adapter, :id user-id
  value: user with keys: adapter, username, id, active?,
  last-active, rooms e.g.
  {:adapter :slack :id 1} {:adapter :slack, :username \"yetibot\", :id 1,
     :active? true, :last-active <DateTime>,
     :rooms #{{:adapter :slack :room \"C123\"}}}"}
  users (atom {}))

(defn create-user
  "Build a data structure representing a user in common adapter-agnostic format.
   Ensures a consistent data structure when creating users from multiple chat
   sources.

   `active?` can be determined by any criteria. In Slack it's managed by
   presence detection. In IRC and Campfire it could be managed by being offline,
   or have an activity timeout. If ommitted, it defaults to true."
  ([username user-info] (create-user username true user-info))
  ([username active? {:keys [id] :as user-info}]
   (let [id (str (or id username))
         mention-name username] ; mention name breaks pure-text representation of user
     (merge user-info {:username username
                       :name username ; alias for backward compat
                       :mention-name mention-name
                       :active? active?
                       :id id
                       :last-active (now)}))))

(defn add-user-merge
  "Knows how to merge a new user with an existing user by combining the set of
   rooms"
  [chat-source]
  (partial
    merge-with
    (fn [existing-user new-user]
      (update-in existing-user [:rooms] conj chat-source))))

(defn add-user-without-room
  [adapter {:keys [id] :as user}]
  "Added for Slack, where we might know about users but don't know the rooms
   that they are in because yetibot is not in those rooms."
  (let [user-key {:adapter adapter :id id}]
    (swap! users assoc user-key user)))

(defn add-user
  "Add a user according to chat-source. If the user already exists, its rooms
   will be merged via `add-user-merge` but all other user properties will remain
   unchanged."
  [chat-source {:keys [id] :as user}]
  (let [user-key {:adapter (:adapter chat-source) :id id}]
    (swap! users (add-user-merge chat-source)
           {user-key (merge user {:rooms #{chat-source}})})))

(defn update-user [source id attrs]
  (let [user-key {:adapter (:adapter source) :id id}]
    ; ensure user exists
    (or (get @users user-key)
        ; the user might not exist if an event came through for a channel that
        ; yetibot wasn't in, since yetibot only builds user models for users it
        ; can listen to in the channels that it's in.
        (throw
          (ex-info (str "User " user-key " doesn't exist")
                   {:causes user-key})))
    (swap! users update-in [user-key] merge attrs)))

(defn remove-user
  "Removes chat-source from the user's :rooms set"
  [chat-source id]
  (let [user-key {:adapter (:adapter chat-source) :id id}]
    (swap! users update-in [user-key :rooms] disj chat-source)))

(defn add-chat-source-to-user
  [chat-source id]
  (let [user-key {:adapter (:adapter chat-source) :id id}]
    (swap! users update-in [user-key :rooms] conj chat-source)))

(defn get-users
  "Returns active users for a given chat source"
  [source]
  (->> @users
       vals
       (filter (fn [u] (and (:active? u) (:rooms u) ((:rooms u) source))))))

(defn get-user [source id]
  (@users {:adapter (:adapter source) :id id}))

(defn find-user-like [chat-source name]
  (let [us (filter (fn [[k user]] (= (:adapter k) (:adapter chat-source)))
                   @users)
        patt (re-pattern (str "(?i)" name))]
    (some (fn [[k v]] (when (re-find patt (or (:name v) "")) v)) us)))

; (def campfire-date-pattern "yyyy/MM/dd HH:mm:ss Z")
; (def date-formatter (doto (new SimpleDateFormat campfire-date-pattern) (.setTimeZone (java.util.TimeZone/getTimeZone "GreenwichEtc"))))

; (defn get-refreshed-user
;   "Returns an already existing user from the atom if available, otherwise a new user with a last_active timestamp"
;   [user]
;   (let [id (:id user)]
;     ))

    ; (get @users id (assoc user :last_active (.format date-formatter (new Date))))))

; (defn get-user-ms [user] (.getTime (.parse date-formatter (:last_active user))))

(defn is-active? [user] (:active? user))

  ; (if (contains? user :last_active)
  ;   (let [current-ms (.getTime (new Date))
  ;         ms-since-active (- current-ms (get-user-ms user))]
  ;     (< ms-since-active active-threshold-milliseconds))
  ;   false))

(defn is-yetibot? [user] false)

(defn get-active-users [] (filter is-active? (vals @users)))

(defn get-active-humans [] (remove is-yetibot? (get-active-users)))

; (defn get-updated-user [id last_active]
;   (assoc (get-user id) :last_active last_active))

; (defn update-active-timestamp [{id :user_id last_active :created_at}]
;   (do
;     (swap! users conj {id (get-updated-user id last_active)})
;     (info @users)))
