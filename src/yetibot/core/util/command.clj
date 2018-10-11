(ns yetibot.core.util.command
  (:require
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.models.help :as help]
    [yetibot.core.parser :refer [parser]]))

(def config-prefix
  (or (:value (get-config sch/Str [:command :prefix])) "!"))

(defn command?
  "Returns true if prefix matches a built-in command or alias"
  [prefix]
  (boolean (help/get-docs-for prefix)))

(defn extract-command
  "Returns the body if it has the command structure with the prefix;
   otherwise nil"
  ([body] (extract-command body config-prefix))
  ([body prefix]
    (re-find (re-pattern (str "^\\" prefix "(.+)")) body)))

(defn embedded-cmds
  "Parse a string and only return a collection of any embedded commands instead
   of the top level expression. Returns nil if there are none."
  [body]
  (->> (parser body)
       second second rest
       ; get expressions
       (filter #(= :expr (first %)))
       ; ensure prefix is actually a command
       (filter #(command? (-> % second second second)))))

