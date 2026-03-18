(ns yetibot.core.commands.react
  (:require
    [clojure.string :as s]
    [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.adapters.adapter :as a]
    [yetibot.core.adapters.discord :as discord]
    [yetibot.core.adapters.slack :as slack]
    [yetibot.core.chat :refer [*target* suppress]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn react-cmd
  "react <emoji> # add a reaction to the last message in the channel"
  {:yb/cat #{:fun}}
  [{emoji :match chat-source :chat-source}]
  (let [adapter-type (:adapter chat-source)]
    (cond
      (= :slack adapter-type)
      (let [adapter (get @a/adapters (:uuid chat-source))
            [_ emoji-name] (re-find #"^:(.+):" emoji)]
        (if emoji-name
          (suppress (slack/react adapter emoji-name *target*))
          {:result/error (str "Couldn't extract emoji from `" emoji "`")}))

      (= :discord adapter-type)
      (let [adapter (get @a/adapters (:uuid chat-source))]
        (discord/react adapter emoji *target*)
        (suppress {})))

      :else
      {:result/error "React only works on Slack and Discord 🎈"})))

(cmd-hook #"react"
  #".+" react-cmd)
