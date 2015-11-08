(ns yetibot.core.handler
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.util :refer [with-fresh-db]]
    [yetibot.core.util.format :refer [to-coll-if-contains-newlines format-exception-log]]
    [yetibot.core.parser :refer [parse-and-eval transformer parser]]
    [clojure.core.match :refer [match]]
    [clojure.core.async :refer [timeout chan go <! >! >!! <!!]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.interpreter :as interp]
    [clojure.string :refer [join]]
    [yetibot.core.models.help :as help]
    [clojure.stacktrace :as st]))

(defn handle-unparsed-expr
  "Top-level entry point for parsing and evaluation of commands"
  ([chat-source user body]
   ; For backward compat, support setting user at this level.
   ; After deprecating, this can be removed.
   ; Currently it's used by the web API in yetibot.
   (info "handle unparsed expr:" chat-source body user)
   (binding [interp/*current-user* user
             interp/*chat-source* chat-source]
     (handle-unparsed-expr body)))
  ([body] (parse-and-eval body)))

(defn handle-parsed-expr
  "Top-level for already-parsed commands. Turns a parse tree into a string or
   collection result."
  [chat-source user parse-tree]
  (binding [interp/*current-user* user
            interp/*chat-source* chat-source]
    (transformer parse-tree)))

(def ^:private exception-format "👮 %s 👮")

(def all-event-types #{:message :leave :enter :sound :kick})

(defn command?
  "Returns true if prefix matches a built-in command or alias"
  [prefix]
  (boolean (help/get-docs-for prefix)))

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

(defn handle-raw
  "No-op handler for optional hooks.
   Expected event-types are:
   :message
   :leave
   :enter
   :sound
   :kick"
  [chat-source user event-type body]
  ; only :message has a body
  (go
    (when body
      ; see if it looks like a command
      (when-let [parsed-cmds
                 (or
                   ; if it starts with a command prefix (!) it's a command
                   (when-let [[_ body] (re-find #"^\!(.+)" body)]
                     [(parser body)])
                   ; otherwise, check to see if there are embedded commands
                   (embedded-cmds body))]
        (with-fresh-db
          (doall
            (map
              #(try
                 (->> %
                      (handle-parsed-expr chat-source user)
                      chat-data-structure)
                 (catch Throwable ex
                   (error "error handling expression:" body
                          (format-exception-log ex))
                   (chat-data-structure (format exception-format ex))))
              parsed-cmds)))))))

(defn cmd-reader [& args] (handle-unparsed-expr (join " " args)))
