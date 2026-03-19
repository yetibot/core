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
          _ (info "discord emoji: channel-info" (pr-str channel-info) "type" (:type channel-info))
          guild-id (:guild-id channel-info)
          _ (info "discord emoji: guild-id" guild-id)
          emojis (if guild-id
                   @(messaging/list-guild-emojis! rest-conn guild-id)
                   {:error "Could not determine guild-id from channel" :channel-info channel-info})
          _ (info "discord emoji: emojis result" (pr-str emojis) "count" (if (vector? emojis) (count emojis) "N/A"))]
      {:result/data emojis
       :result/value (if (vector? emojis)
                       (map :name emojis)
                       [(:error emojis)])})))

(cmd-hook #"discord"
  #"emoji" emoji-cmd)
