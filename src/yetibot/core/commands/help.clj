(ns yetibot.core.commands.help
  (:require
    [clojure.string :as s]
    [yetibot.core.models.help :refer [fuzzy-get-docs-for get-docs get-docs-for]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def separator "▬▬▬")

(defn help-topics
  [_]
  (str "Use `help <command>` on any of the following for more details."
       \newline
       "Use `alias` command to list available aliases"
       \newline
       "Use `category` to list command by their category, "
       " e.g. `category list fun`."
       \newline
       \newline
       "Available commands:"
       \newline
       \newline
       (->>
        (get-docs)
        keys
        sort
        (map #(str "`" % "`"))
        (s/join ", "))))

(defn help-for-topic
  "help <topic> # get help for <topic>. If no exact matches are found for topic, it will fallback to the topic with the smallest Levenshtein distance < 2"
  {:yb/cat #{:util}}
  [{prefix :args}]
  (or
    (seq (get-docs-for prefix))
    (seq (fuzzy-get-docs-for prefix))
    {:result/error
     (format "I couldn't find any help for topic '%s'" prefix)}))

(defn help-all-cmd
  "help all # get help for all topics"
  {:yb/cat #{:util}}
  [_]
  (s/join (str \newline separator \newline)
          (for [section (vals (get-docs))]
            (s/join \newline section))))

(cmd-hook #"help"
  #"^all$" help-all-cmd
  #"^$" help-topics
  #"^\S+$" help-for-topic)
