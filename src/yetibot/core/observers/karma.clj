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

(def add-karma (partial adjust-karma "++"))

(def remove-karma (partial adjust-karma "--"))

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
                        pos-reaction (-> event-info parse-react-event add-karma)
                        neg-reaction (-> event-info parse-react-event remove-karma)
                        nil)]
    (chat-data-structure (fmt-response response))))

(defn message-hook
  [event-info]
  (when-let [parsed-event (parse-message-event event-info)]
    (-> (apply adjust-karma parsed-event)
        fmt-response
        chat-data-structure)))

(obs-hook #{:react} #'reaction-hook)
(obs-hook #{:message} #'message-hook)
