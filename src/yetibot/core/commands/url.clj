(ns yetibot.core.commands.url
  (:require
    [clojure.string :as s]
    [yetibot.core.config :refer [get-config]]
    [cemerick.url :refer [url]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn config [] (get-config String [:url]))

(defn yetibot-url
  "Given a path build a fully qualified URL"
  [& paths]
  (str (apply url (config) paths)))

(defn url-cmd
  "url # get Yetibot's configured web address"
  [_]
  (:value (config)))

(cmd-hook #"url"
  _ url-cmd)
