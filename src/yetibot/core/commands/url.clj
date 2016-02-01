(ns yetibot.core.commands.url
  (:require
    [clojure.string :as s]
    [yetibot.core.config :refer [get-config]]
    [cemerick.url :refer [url]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn config [] (get-config :yetibot :url))

(defn yetibot-url
  "Given a path build a fully qualified URL"
  [path]
  (-> (url (config)
           path)
      str))

(defn url-cmd
  "url # get Yetibot's configured web address"
  [_]
  (config))

(cmd-hook #"url"
  _ url-cmd)
