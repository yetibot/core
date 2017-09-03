(ns yetibot.core.commands.about
  (:require [yetibot.core.hooks :refer [cmd-hook]]))

(defn about-cmd
  "about # about Yetibot"
  {:yb/cat #{:info}}
  [& _]
  {:result/data
   {:about/url "http://yetibot.com"
    :about/name "Yetibot"
    :about/logo
    "https://github.com/devth/yetibot/raw/master/img/yetibot_final.png?raw=true"
    :about/description
    (str
      "Yetibot is a chat bot written in Clojure;"
      \newline
      "it wants to make your life easier;"
      \newline
      "it wants you to have fun.")
    :about/author "@devth"}
   :result/value
   ["https://github.com/devth/yetibot/raw/master/img/yetibot_final.png?raw=true"
    "Yetibot is a chat bot written in Clojure;"
    "it wants to make your life easier;"
    "it wants you to have fun."
    "http://yetibot.com"]})

(cmd-hook #"about"
  _ about-cmd)
