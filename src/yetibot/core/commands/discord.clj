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
          _ (info "discord emoji: channel-id" channel-id "chat-source" (pr-str chat-source))
          rest-conn (:rest @(:conn adapter))
          channel-info @(messaging/get-channel! rest-conn channel-id)
          _ (info "discord emoji: channel-info" (pr-str channel-info))
          guild-id (:guild-id channel-info)
          _ (info "discord emoji: guild-id" guild-id)
          emojis @(messaging/list-guild-emojis! rest-conn guild-id)
          _ (info "discord emoji: emojis count" (count emojis))]
      {:result/data emojis
       :result/value (map :name emojis)})))

(cmd-hook #"discord"
  #"emoji" emoji-cmd)
