(ns yetibot.core.commands.help
  (:require [clojure.string :as s])
  (:use [yetibot.core.hooks :only [cmd-hook]]
        [yetibot.core.models.help :only (get-docs get-docs-for)]))

(def separator
  "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")

(defn help-topics
  [_]
  (str "Use help <topic> for more details"
       \newline
       (s/join ", "
               (sort (keys (get-docs))))))

(defn help-for-topic
  "help <topic> # get help for <topic>"
  [{prefix :args}]
  (or
    (seq (get-docs-for prefix))
    (format "I couldn't find any help for topic '%s'" prefix)))

(defn help-all-cmd
  "help all # get help for all topics"
  [_]
  (s/join (str \newline separator \newline)
          (for [section (vals (get-docs))]
            (s/join \newline section))))

(cmd-hook #"help"
          #"^all$" help-all-cmd
          #"^$" help-topics
          #"^\S+$" help-for-topic)
