(ns yetibot.core.handler
  (:require
    [clojure.core.async :refer [timeout chan go <! >! >!! <!!]]
    [clojure.core.match :refer [match]]
    [clojure.stacktrace :as st]
    [clojure.string :refer [join]]
    [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.util.command :refer [command? extract-command embedded-cmds]]
    [yetibot.core.interpreter :as interp]
    [yetibot.core.models.history :as h]
    [yetibot.core.parser :refer [parse-and-eval transformer parser unparse]]
    [yetibot.core.util.format :refer [to-coll-if-contains-newlines
                                      format-data-structure
                                      format-exception-log]]))

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

(defn handle-raw
  "Top level handler for commands. Properly records commands in the database,
   handles errors, and posts the result back to chat.

   Expected event-types are:
   :message
   :leave
   :enter
   :sound
   :kick"
  [{:keys [adapter room uuid is-private] :as chat-source}
   user event-type body yetibot-user]
  (go
    ;; Note: only :message has a body
    (when body
      #_(debug "handle-raw" body user event-type)
      (let [timestamp (System/currentTimeMillis)
            correlation-id (str timestamp "-"
                                (hash [chat-source user event-type body]))

            parsed-normal-command
            (when-let [[_ body] (extract-command body)] (parser body))

            parsed-cmds
            (or
              ;; if it starts with a command prefix (e.g. !) it's a command
              (and parsed-normal-command [parsed-normal-command])
              ;; otherwise, check to see if there are embedded commands
              (embedded-cmds body))
            cmd? (boolean (seq parsed-cmds))]

        ;; record the body of users' (not Yetibot) messages
        (when-not (:yetibot? user)
          (h/add {:chat-source-adapter uuid
                  :chat-source-room room
                  :is-private is-private
                  :correlation-id correlation-id
                  :user-id (-> user :id str)
                  :user-name (-> user :username str)
                  :is-yetibot false
                  :is-command cmd?
                  :body body}))

        ;; When:
        ;; - the user's input was a command (or contained embedded commands)
        ;; - and the user is not Yetibot
        ;; process those commands:
        ;; - adding them individually to history and
        ;; - posting them to chat
        (when (and cmd? (not (:yetibot? user)))
          (run!
            (fn [parse-tree]
              (try
                (let [original-command-str (unparse parse-tree)
                      {:keys [value error]} (handle-parsed-expr chat-source user
                                                                parse-tree)
                      result (or value error)
                      error? (not (nil? error))
                      [formatted-response _] (format-data-structure result)]
                  ;; Yetibot should record its own response in `history` table
                  ;; before/during posting it back to the chat adapter. Then we
                  ;; can more easily correlate request (e.g. commands from user)
                  ;; and response (output from Yetibot)
                  (h/add {:chat-source-adapter uuid
                          :chat-source-room room
                          :is-private is-private
                          :correlation-id correlation-id
                          :user-id (-> yetibot-user :id str)
                          :user-name (-> yetibot-user :username str)
                          :is-yetibot true
                          :is-command false
                          :is-error error?
                          :command original-command-str
                          :body formatted-response})
                  ;; don't report errors on embedded commands
                  (if (or (not error?) parsed-normal-command)
                    (chat-data-structure result)
                    (info "Not sending error result for embedded command to
                           chat" result)))
                (catch Throwable ex
                  (error "error handling expression:" body
                         (format-exception-log ex))
                  (chat-data-structure (format exception-format ex)))))
            parsed-cmds))))))

(defn cmd-reader [& args] (handle-unparsed-expr (join " " args)))
