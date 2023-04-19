(ns yetibot.core.commands.url
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(s/def ::config string?)

(defn config [] (get-config ::config [:url]))

(defn url-cmd
  "url # get Yetibot's configured web address"
  [_]
  (:value (config)))

(cmd-hook #"url"
  _ url-cmd)
