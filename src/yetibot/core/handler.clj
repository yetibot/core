(ns yetibot.core.handler
  (:require
   [yetibot.core.config :refer [get-config]]
   [clojure.core.async :refer [timeout alts!! go]]
   [clojure.spec.alpha :as s]
   [clojure.string :refer [blank? join]]
   [taoensso.timbre :refer [trace info error]]
   [yetibot.core.chat :refer [chat-data-structure]]
   [yetibot.core.util.command :refer [extract-command embedded-cmds]]
   [yetibot.core.interpreter :as interp]
   [yetibot.core.models.history :as h]
   [yetibot.core.parser :refer [parse-and-eval transformer parser unparse]]
   [yetibot.core.util.format :refer [format-data-structure
                                     format-exception-log]]))

(s/def ::embedded-commands-enabled-config string?)

(defn embedded-enabled?
  "Determine whether or not embedded commands are enabled.

   Embedded commands look like:

   The weather looks nice today `weather seattle`

   instead of the more explicit command execution syntax:

   !weather seattle

   This means multiple embedded commands can be contained within a single
   message, such as:

   `weather seattle` `weather new york`

   However, some teams may find the overloading of backtick syntax disruptive,
   so it can be globally disabled in configuration.

   In the future we'll also allow this setting to be overriden on a per-channel
   basis.

   It is enabled by default."
  []
  (let [{value :value} (get-config ::embedded-commands-enabled-config
                                   [:command :embedded :enabled])]
    (if-not (blank? value)
      (not= "false" value)
      ;; enabled by default
      true)))

(def expr-eval-timeout-ms 5000)

(def ^:private exception-format "👮 %s 👮")

(def all-event-types #{:message :leave :enter :sound :kick :react})

(defn ->correlation-id
  "Lets us correlate two records in the history table by creating a unique id
   based on a timestap and the user and message body. Correlation is for:
   - an input command from a user
   - the result that Yetibot posts back to chat"
  [body user]
  (str (System/currentTimeMillis)
       "-"
       (hash [interp/*chat-source* user body])))

(defn ->parsed-message-info
  "Helper to transform body into a message related info map
   that is used by internal functions"
  [body]
  (let [parsed-normal-command (when-let [[_ cmd-body] (extract-command body)]
                                (parser cmd-body))
        parsed-cmds (or (and parsed-normal-command [parsed-normal-command])
                        (when (embedded-enabled?) (embedded-cmds body)))
        cmd? (boolean (seq parsed-cmds))]
    {:parsed-normal-command parsed-normal-command
     :parsed-cmds parsed-cmds
     :cmd? cmd?}))

(defn add-user-message-to-history
  "When the user is not Yetibot, it will add the user's messages to history"
  [{:keys [body user correlation-id] :as _cmd-param-map}]
  (when (and user (not (:yetibot? user)))
    (let [{:keys [room uuid is-private]} interp/*chat-source*
          {:keys [cmd?]} (->parsed-message-info body)]
      (h/add {:chat-source-adapter uuid
              :chat-source-room room
              :is-private is-private
              :correlation-id correlation-id
              :user-id (-> user :id str)
              :user-name (-> user :username str)
              :is-yetibot false
              :is-command cmd?
              :body body}))))

(defn handle-parsed-expr
  "Top-level for already-parsed commands.
   Turns a parse tree into a string or collection result."
  [{:keys [user yetibot-user] :as _cmd-param-map}
   parse-tree]
  (binding [interp/*current-user* user
            interp/*yetibot-user* yetibot-user]
    (transformer parse-tree)))

(defn ->handled-expr-info
  "Helper to transform a handled user expression (command) and return an info
   map about the handled command, such as if errored, the result, formatted
   response, and the original command string"
  [{:keys [value error] :as _handle-parsed-expr} parse-tree]
  (let [original-command-str (unparse parse-tree)
        result (or value error)
        error? (not (nil? error))
        [formatted-response _] (format-data-structure
                                result)]
    {:original-command-str original-command-str
     :result result
     :error? error?
     :formatted-response formatted-response}))

(defn add-bot-response-to-history
  "When `record-yetibot-response?` is true, it will add the bot's
   response to history table, before/during posting it back to
   the chat adapter. Then we can more easily correlate request (e.g. commands
   from user) and response (output from Yetibot)"
  [{:keys [original-command-str formatted-response error?]
    :as _handled-expr-info}
   {:keys [yetibot-user record-yetibot-response? correlation-id]
    :as _cmd-param-map}]
  (let [{:keys [room uuid is-private]} interp/*chat-source*]
    (trace record-yetibot-response? "recording history" uuid room is-private
           original-command-str formatted-response)
    (when record-yetibot-response?
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
              :body formatted-response}))))

(defn ->handled-expr-results
  [body results]
  (or results
      [{:timeout? true
        :result (str "Evaluation of `" body "` timed out after "
                     (/ expr-eval-timeout-ms 1000) " seconds.")}]))

(defn ->cmd-param-map
  "returns a parameter map related to the handling of a command"
  [body user yetibot-user record-yetibot-response?]
  (let [{:keys [parsed-normal-command parsed-cmds cmd?]}
        (->parsed-message-info body)]
    {:body body
     :user user
     :yetibot-user yetibot-user
     :record-yetibot-response? record-yetibot-response?
     :correlation-id (->correlation-id body user)
     :parsed-normal-command parsed-normal-command
     :parsed-cmds parsed-cmds
     :cmd? cmd?
     ;; the user's input was an expression (or contained embedded exprs)
     ;; and the user is not Yetibot
     :non-yetibot-cmd (and cmd? (not (:yetibot? user)))
     :embedded? (not parsed-normal-command)}))

(defn ->successfully-handled-expr
  [{:keys [error? result] :as _handled-expr-info}
   {:keys [embedded?] :as _cmd-param-map}]
  {:embedded? embedded?
   :error? error?
   :result result})

(defn ->unsuccessfully-handled-expr
  [{:keys [body embedded?] :as _cmd-param-map}
   ex]
  (error "error handling expression:" body (format-exception-log ex))
  {:embedded? embedded?
   :error? true
   :result (format exception-format ex)})

(defn eval-expr-and-record-response
  [parse-tree cmd-param-map]
  (try
    (let [handled-expr-info (->handled-expr-info
                             (handle-parsed-expr
                              cmd-param-map
                              parse-tree)
                             parse-tree)]

      (add-bot-response-to-history handled-expr-info
                                   cmd-param-map)

      (->successfully-handled-expr handled-expr-info
                                   cmd-param-map))
    (catch Throwable ex
      (->unsuccessfully-handled-expr cmd-param-map ex))))

(defn record-and-run-raw
  "Top level message handler.

   Use this when you want to evaluate a command and all the dynamic vars have
   already been bound.

   At the end of the expression pipeline a :value or :error will be returned.
   Unwraps it and returns it formatted for the user.

   body - unparsed expression. Could be one of:
          1. A command like '!echo hello yetibot'
          2. Embedded commands like 'nice day today `weather` `echo foo`'
          3. Normal body text without commands.

   When the body was an expression, it returns:

   [
     {
      :embedded? - whether or not the command was embedded
      :error? - whether or not the result was an error
      :result - the formatted string result
      :timeout? - set to true if the command timed out
     }
   ]

   Otherwise returns nil for non expressions.
   "
  [body user yetibot-user & [{:keys [record-yetibot-response?]
                              :or {record-yetibot-response? true}}]]
  (trace
   "record-and-run-raw" body record-yetibot-response? interp/*chat-source*)
  (let [cmd-param-map (->cmd-param-map user
                                       body
                                       yetibot-user
                                       record-yetibot-response?)]
    (add-user-message-to-history cmd-param-map)
    (when (:non-yetibot-cmd cmd-param-map)
      (let [[results _timeout-result]
            (alts!!
             [(go (map
                   (fn [parse-tree]
                     (eval-expr-and-record-response parse-tree cmd-param-map))
                   (:parsed-cmds cmd-param-map)))
              (timeout expr-eval-timeout-ms)])]
        (->handled-expr-results body results)))))

(defn handle-unparsed-expr
  "Entry point for parsing and evaluation of ad-hoc commands.
   These are not recorded in the history table."
  ([chat-source user body]
   ; For backward compat, support setting user at this level.
   ; After deprecating, this can be removed.
   ; Currently it's used by the web API in yetibot.
   (binding [interp/*current-user* user
             interp/*chat-source* chat-source]
     (handle-unparsed-expr body)))
  ([body] (parse-and-eval body)))

(defn dispatch-command-response
  "When a command response is valid and succesful, it will be passed along
   to be formatted and send to chat adapter, otherwise, log and return nil"
  [{:keys [embedded? error? result] :as _handled-expr-results}]
  (if (or (not error?) (not embedded?))
    (chat-data-structure result)
    (info "Skip sending error result for embedded command to chat" result)))

(defn handle-raw
  "Top level handler for commands. Properly records commands in the database,
   handles errors, and posts the result back to chat.

   This is hook'd for obs-hook handlers.

   Expected event-types are:

   :message
   :leave
   :enter
   :sound
   :kick
   :react"
  [chat-source user event-type yetibot-user
   {:keys [body]}]
  ;; Note: only :message and :react have a body
  (when (and body (= event-type :message))
    (binding [interp/*chat-source* chat-source]
      (go
        ;; there may be multiple expr-results, as in the case of multiple
        ;; embedded commands in a single body
        (let [expr-results (record-and-run-raw body user yetibot-user)]
          (run!
           dispatch-command-response
           expr-results))))))

(defn cmd-reader [& args] (handle-unparsed-expr (join " " args)))
