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

(defn- parse-event-info
  [e]
  {:voter-name (-> e :user :name)
   :voter-id   (format "@%s" (-> e :user :id))
   :user-id    (format "@%s" (-> e :message-user :id))})

(defn- adjust-karma
  [event-info, action]
  (let [{:keys [voter-name voter-id user-id]} (parse-event-info event-info)]
    (karma/adjust-score {:user {:id voter-id :name voter-name}
                         :match ["_" user-id action]})))

(defn- add-karma [e] (adjust-karma e "++"))

(defn- remove-karma [e] (adjust-karma e "--"))

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
  (if-let [response (condp = (:reaction event-info)
                      pos-reaction (add-karma event-info)
                      neg-reaction (remove-karma event-info)
                      nil)]
    (chat-data-structure (fmt-response response))))

(obs-hook #{:react} #'reaction-hook)
