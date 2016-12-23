(ns yetibot.core.models.mail
  (:require
    [yetibot.core.schema :as ys]
    [schema.core :as sc]
    [overtone.at-at :refer [mk-pool every stop show-schedule]]
    [clojure.string :as s]
    [inflections.core :refer [pluralize]]
    [yetibot.core.config :refer [get-config]]
    [clojure-mail
     [core :as mail]
     [message :as msg]]))

(def mail-schema
  {:host ys/non-empty-str
   :user ys/non-empty-str
   :pass ys/non-empty-str
   :from ys/non-empty-str
   (sc/optional-key :bcc) sc/Str})

(defn config [] (get-config mail-schema [:mail]))

(defn configured? [] (nil? (:error (config))))

;; TODO - move into start
(when (configured?)
  (let [{:keys [user pass]} (:value (config))]
    (def store (mail/store user pass))))

(def pool (mk-pool))
(def poll-interval (* 1000 60))
(def folder "INBOX")

; reading helpers
(defn- clean-newlines [body]
  (s/replace body #"\r\n" "\n"))

(defn- plain-key [m] (first (filter #(re-find #"TEXT/PLAIN" %) (keys m))))
(defn- plain-body [m]
  (let [body (first (:body m))]
    (when body (clean-newlines (body (plain-key body))))))

(defn- read-mail [m] ((juxt :from :subject plain-body) m))

(defn fmt-messages [messages]
  (apply concat (interleave
                  (map read-mail messages)
                  (repeat ["--"]))))

(defn- fmt-you-have-mail [messages]
  (cons (format "You have mail! %s:\n" (pluralize (count messages) "new message"))
        (fmt-messages messages)))

(defn fetch-unread-mail []
  (let [messages (mail/unread-messages folder)]
    (when-not (empty? messages)
      (mail/mark-all-read folder)
      (fmt-you-have-mail messages))))

(defn fetch-and-announce-unread-mail []
  (let [formatted-mail (fetch-unread-mail)]
    (when formatted-mail
      (yetibot.core.chat/chat-data-structure formatted-mail))))

; poll for new messages
(defonce initial
  (future (every poll-interval fetch-and-announce-unread-mail pool
                 :desc "Fetch email"
                 :initial-delay 0)))
