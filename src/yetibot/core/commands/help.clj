(ns yetibot.core.commands.help
  (:require
   [clojure.string :as s]
   [yetibot.core.models.default-command :refer [fallback-enabled?
                                                configured-default-command]]
   [yetibot.core.models.help :refer [fuzzy-get-docs-for get-docs get-docs-for
                                     get-alias-docs]]
   [yetibot.core.hooks :refer [cmd-hook]]))

(def separator "▬▬▬")

(defn fallback-help-text
  []
  (if (fallback-enabled?)
    (str
     "✅ Fallback commands are enabled, and the default command is `"
     (configured-default-command)
     "`. This is triggered when a user enters a command that does not exist, "
     "and passes whatever the user entered as args to the fallback command.")
    "🚫 Fallback commands are disabled"))

(defn alias-help-text
  []
  (let [ads (get-alias-docs)]
    (when-not (empty? ads)
      (str
       \newline
       \newline
       "Available aliases:"
       \newline
       \newline
       (->>
        ads
        keys
        sort
        (map #(str "`" % "`"))
        (s/join ", "))))))

(defn help-topics
  [_]
  (str "Use `help <command>` on any of the following for more details."
       \newline
       "Use `alias` command to list available aliases"
       \newline
       "Use `category` to list command by their category, "
       " e.g. `category list fun`."
       \newline
       (fallback-help-text)
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
        (s/join ", "))
       (alias-help-text)))

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
