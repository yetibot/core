(ns yetibot.core.observers.karma
  (:require
   [yetibot.core.models.channel :as channel]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [yetibot.core.chat :refer [chat-data-structure]]
   [yetibot.core.hooks :refer [obs-hook]]
   [yetibot.core.commands.karma :as karma]))

(defn- emoji-shortcode->reaction
  "When Slack delivers reaction events it reformats the emoji shortcodes,
   removing colons and replacing underscores with spaces. We provide that same
   functionality, here, so we can more easily compare delivered events.

   :thunder_cloud_and_rain: is delivered as 'thunder cloud and rain'"
  [emoji-shortcode]
  (-> (re-matches #":(.+):" emoji-shortcode)
      second
      (str/replace "_" " ")))

(def pos-reaction (emoji-shortcode->reaction karma/pos-emoji))
(def neg-reaction (emoji-shortcode->reaction karma/neg-emoji))

(def cmd-re (re-pattern
             (str "^(?x) \\s* (" karma/pos-emoji "|" karma/neg-emoji ") \\s*"
                  "@(\\w[-\\w]*\\w) \\s*"
                  "(?:--|\\+\\+)?"
                  "(?: \\s+(.+) )? \\s*$")))

(defn- parse-react-event
  [{:keys [chat-source] :as e}]
  (log/info (log/color-str :yellow "parse-react-event" (pr-str e)))
  {:chat-source chat-source
   :voter-name (-> e :user :name)
   :voter-id   (-> e :user :id)
   :user-id    (-> e :message-user :id)})

(defn- parse-message-event
  [{body :body user :user chat-source :chat-source}]
  (when-let [[_ action user-id note] (re-matches cmd-re body)]
    (let [action (if (= action karma/pos-emoji) "++" "--")]
      [action
       {:chat-source chat-source
        :voter-name (:name user)
        :voter-id   (:id user)
        :user-id    user-id
        :note       note}])))

(defn- adjust-karma
  [action {:keys [voter-id voter-name user-id note chat-source]}]
  (karma/adjust-score {:chat-source chat-source
                       :user {:id voter-id :name voter-name}
                       :match ["_" user-id action note]}))

(def inc-karma (partial adjust-karma "++"))
(def dec-karma (partial adjust-karma "--"))

(defn reaction-hook
  [event-info]
  (when-let [response (condp = (:reaction event-info)
                        pos-reaction (-> event-info parse-react-event inc-karma)
                        neg-reaction (-> event-info parse-react-event dec-karma)
                        nil)]
    (:result/value response)))

(defn message-hook
  [event-info]
  (when-let [parsed-event (parse-message-event event-info)]
    (:result/value (apply adjust-karma parsed-event))))

;; chat-data-structure requires some run-time state so we're pulling
;; it out of our hook fns so they can be tested.
(defn hook-wrapper
  [f {:keys [settings] :as event}]
  ;; only enable karma obs on a per channel basis
  (when (= (settings channel/karma-enabled-key) "true")
    (when-let [response (f event)]
      (chat-data-structure response))))

(obs-hook #{:react} (partial hook-wrapper reaction-hook))
(obs-hook #{:message} (partial hook-wrapper message-hook))
