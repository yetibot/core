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
          channel-id (:room chat-source)
          rest-conn (:rest @(:conn adapter))
          channel-info @(messaging/get-channel! rest-conn channel-id)
          guild-id (:guild-id channel-info)
          emojis @(messaging/list-guild-emojis! rest-conn guild-id)]
      {:result/data emojis
       :result/value (map :name emojis)})))

(cmd-hook #"discord"
  #"emoji" emoji-cmd)
