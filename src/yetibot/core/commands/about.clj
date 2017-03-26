(ns yetibot.core.commands.about
  (:require [yetibot.core.hooks :refer [cmd-hook]]))

(defn about-cmd
  "about # about Yetibot"
  {:yb/cat #{:info}}
  [& _]
  ["https://github.com/devth/yetibot/raw/master/img/yetibot_final.png?raw=true"
   "Yetibot is a chat bot written in Clojure;"
   "it wants to make your life easier;"
   "it wants you to have fun."
   "http://yetibot.com"])

(cmd-hook #"about"
  _ about-cmd)
