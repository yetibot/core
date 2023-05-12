(ns yetibot.core.commands.slack
  (:require
   [yetibot.core.adapters.adapter :as a]
   [clojure.string :as s]
   [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.adapters.slack :as slack]
   [clj-slack.emoji :as emoji]
   [yetibot.core.chat :refer [*target* suppress]]
   [yetibot.core.hooks :refer [cmd-hook]]))

(defn emoji-cmd
  "slack emoji # list custom Slack emoji"
  [{:keys [chat-source]}]
  (if (not= :slack (:adapter chat-source))
    {:result/error "slack emoji only works on Slack 🎈"}
    (let [adapter (get @a/adapters (:uuid chat-source))
          conn (:conn adapter)
          config (:config adapter)
          emojis (:emoji (emoji/list (slack/slack-config config)))]
      {:result/data emojis
       :result/value (map (fn [[k]] (str ":" (name k) ":")) emojis)})))

(cmd-hook #"slack"
  #"emoji" emoji-cmd)

