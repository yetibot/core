(ns yetibot.core.observers.karma
  (:require
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [yetibot.core.chat :refer [chat-data-structure]]
   [yetibot.core.hooks :refer [obs-hook]]
   [yetibot.core.commands.karma :as karma]))

(defn- emoji-shortcode->reaction
  [emoji-shortcode]
  (-> (re-matches #":(.+):" emoji-shortcode)
      second
      (str/replace "_" " ")))

(def pos-reaction (emoji-shortcode->reaction karma/pos-emoji))
(def neg-reaction (emoji-shortcode->reaction karma/neg-emoji))

(def cmd-re (re-pattern
             (str "^(?x) \\s* (" karma/pos-emoji "|" karma/neg-emoji ") \\s*"
                  "(@\\w[-\\w]*\\w) \\s*"
                  "(?:--|\\+\\+)?"
                  "(?: \\s+(.+) )? \\s*$")))

(defn- parse-react-event
  [e]
  {:voter-name (-> e :user :name)
   :voter-id   (format "@%s" (-> e :user :id))
   :user-id    (format "@%s" (-> e :message-user :id))})

(defn- parse-message-event
  [{body :body user :user}]
  (when-let [[_ action user-id note] (re-matches cmd-re body)]
    (let [action (if (= action karma/pos-emoji) "++" "--")]
      [action
       {:voter-name (:name user)
        :voter-id   (format "@%s" (:id user))
        :user-id    user-id
        :note       note}])))

(defn- adjust-karma
  [action {:keys [voter-id voter-name user-id note]}]
  (karma/adjust-score {:user {:id voter-id :name voter-name}
                       :match ["_" user-id action note]}))

(def inc-karma (partial adjust-karma "++"))
(def dec-karma (partial adjust-karma "--"))

(defn- fmt-response
  [result]
  (if (contains? result :result/error)
    (:result/error result)
    (format "%s <%s>: %d"
            (:result/value result)
            (-> result :result/data :user-id)
            (-> result :result/data :score))))

(defn reaction-hook
  [event-info]
  (when-let [response (condp = (:reaction event-info)
                        pos-reaction (-> event-info parse-react-event inc-karma)
                        neg-reaction (-> event-info parse-react-event dec-karma)
                        nil)]
    (fmt-response response)))

(defn message-hook
  [event-info]
  (when-let [parsed-event (parse-message-event event-info)]
    (fmt-response (apply adjust-karma parsed-event))))

;; chat-data-structure requires some run-time state so we're pulling
;; it out of our hook fns so they can be tested.
(defn hook-wrapper
  [f event]
  (when-let [response (f event)]
    (chat-data-structure response)))

(def reaction-hook-wrapper (partial hook-wrapper reaction-hook))
(def message-hook-wrapper (partial hook-wrapper message-hook))

(obs-hook #{:react} #'reaction-hook-wrapper)
(obs-hook #{:message} #'message-hook-wrapper)
