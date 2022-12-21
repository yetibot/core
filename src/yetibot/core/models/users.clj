(ns yetibot.core.models.users
  "This is an in-memory representation of users by adapter and channel"
  (:require
    [taoensso.timbre :refer [debug]]
    [clj-time.core :refer [now]]))

(def config {:active-threshold-milliseconds (* 15 60 1000)})

(defonce
  ^{:doc
    "key is a map like: {:adapter adapter, :id user-id}
     value is a user with keys:
       adapter, username, id, active?, last-active, channels

     e.g.:

     {:adapter :slack :id 1}
     {:adapter :slack, :username \"yetibot\", :id 1,
      :active? true, :last-active <DateTime>,
      :channels #{{:adapter :slack :room \"C123\"}}}"}
  users (atom {}))

(def min-user-keys
  "The minimum set of keys all users have regardless of adapter"
  [:username :name :mention-name :active? :id :last-active])

(defn create-user
  "Build a data structure representing a user in common adapter-agnostic format.
   Ensures a consistent data structure when creating users from multiple chat
   sources.

   `active?` can be determined by any criteria. In Slack it's managed by
   presence detection. In IRC it could be managed by being offline, or have an
   activity timeout. If ommitted, it defaults to true."
  ([username user-info] (create-user username true user-info))
  ([username active? {:keys [id] :as user-info}]
   (let [id (str (or id username))
         mention-name username] ; mention name breaks pure-text representation of user
     (merge user-info {:username username
                       :name username ; alias for backward compat
                       :mention-name mention-name ; for display
                       :active? active?
                       :id id
                       :last-active (now)}))))

(defn add-user-merge
  "Knows how to merge a new user with an existing user by combining the set of
   channels"
  [chat-source]
  (partial
    merge-with
    (fn [existing-user _new-user]
      (update-in existing-user [:channels] conj chat-source))))

(defn add-user-without-channel
  "Added for Slack, where we might know about users but don't know the channels
   that they are in because yetibot is not in those channels or we just don't
   have that data available."
  [adapter {:keys [id] :as user}]
  (let [user-key {:adapter adapter :id id}]
    (swap! users assoc user-key user)))

(defn add-user
  "Add a user according to chat-source. If the user already exists, its channels
   will be merged via `add-user-merge` but all other user properties will remain
   unchanged."
  [chat-source {:keys [id] :as user}]
  (let [user-key {:adapter (:adapter chat-source) :id id}]
    (swap! users (add-user-merge chat-source)
           {user-key (merge user {:channels #{chat-source}})})))

(defn update-user [source id attrs]
  (debug "update-user" source id attrs)
  (let [user-key {:adapter (:adapter source) :id id}]
    ;; ensure user exists
    (when-not (get @users user-key)
      ;; the user might not exist if an event came through for a channel that
      ;; yetibot wasn't in, since yetibot only builds user models for users it
      ;; can listen to in the channels that it's in.
      (throw
        (ex-info (str "User " user-key " doesn't exist")
                 {:causes user-key})))
    (swap! users update-in [user-key] merge attrs)))

(defn remove-user
  "Removes chat-source from the user's :channels set"
  [chat-source id]
  (let [user-key {:adapter (:adapter chat-source) :id id}]
    (swap! users update-in [user-key :channels] disj chat-source)))

(defn add-chat-source-to-user
  [chat-source id]
  (let [user-key {:adapter (:adapter chat-source) :id id}]
    (swap! users update-in [user-key :channels] conj chat-source)))

(defn get-all-users
  "Returns a list of all users"
  []
  (vals @users))

(defn get-users
  "Returns users for a given chat source"
  [source]
  (let [chat-source (select-keys source [:adapter :uuid :room])]
    (->> @users
         vals
         (filter (fn [{:keys [channels]}]
                   (and channels ((set channels) chat-source)))))))

(defn get-user [source id]
  (@users {:adapter (:adapter source) :id id}))

(defn find-user-like [chat-source name]
  (let [us (filter (fn [[k _user]] (= (:adapter k) (:adapter chat-source)))
                   @users)
        patt (re-pattern (str "(?i)" name))]
    (some (fn [[_k v]] (when (re-find patt (or (:name v) "")) v)) us)))

(defn is-active? [user] (:active? user))

(defn is-yetibot? [_user] false)

(defn get-active-users [] (filter is-active? (vals @users)))

(defn get-active-humans [] (remove is-yetibot? (get-active-users)))

; (defn get-updated-user [id last_active]
;   (assoc (get-user id) :last_active last_active))

; (defn update-active-timestamp [{id :user_id last_active :created_at}]
;   (do
;     (swap! users conj {id (get-updated-user id last_active)})
;     (info @users)))

;; TODO use a db-specific uuid instead of adapter-specific IDs, which may
;; collide

(defn get-user-by-id [id]
  (first
    (filter #(= id (:id %)) (vals @users))))
