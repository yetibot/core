(ns yetibot.core.commands.react
  (:require
    [clojure.string :as s]
    [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.adapters.adapter :as a]
    [yetibot.core.adapters.slack :as slack]
    [yetibot.core.chat :refer [*target*]]
    [yetibot.core.hooks :refer [cmd-hook suppress]]))

(defn react-cmd
  "react <emoji> # add a Slack reaction to the last message in the channel"
  {:yb/cat #{:fun}}
  [{emoji :match chat-source :chat-source}]
  (debug chat-source)
  (if (not= :slack (:adapter chat-source))
    "React only works on Slack 🎈"
    (let [adapter (get @a/adapters (:uuid chat-source))
          [_ emoji-name] (re-find #"^:(.+):$" emoji)]
     (suppress (slack/react adapter emoji-name *target*)))))

(cmd-hook #"react"
  #".+" react-cmd)
