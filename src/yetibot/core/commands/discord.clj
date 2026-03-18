(ns yetibot.core.commands.discord
  (:require
    [yetibot.core.adapters.adapter :as a]
    [discljord.messaging :as messaging]
    [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.chat :refer [*target* suppress]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn emoji-cmd
  "discord emoji # list custom Discord emoji"
  [{:keys [chat-source]}]
  (if (not= :discord (:adapter chat-source))
    {:result/error "discord emoji only works on Discord 🎈"}
    (let [adapter (get @a/adapters (:uuid chat-source))
          guild-id (:guild-id chat-source)
          emojis @(messaging/list-guild-emojis! (:rest @(:conn adapter)) guild-id)]
      {:result/data emojis
       :result/value (map :name emojis)})))

(cmd-hook #"discord"
  #"emoji" emoji-cmd)
