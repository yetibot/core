(ns yetibot.core.commands.observe
  (:require
    [clojure.core.async :refer [go]]
    [selmer.parser :refer [render]]
    [clojure.string :as s :refer [split trim]]
    [clojure.tools.cli :refer [parse-opts]]
    [taoensso.timbre :refer [color-str debug info]]
    [yetibot.core.chat :refer [*thread-ts* chat-data-structure]]
    [yetibot.core.db.observe :as model]
    [yetibot.core.handler :refer [record-and-run-raw all-event-types]]
    [yetibot.core.hooks :refer [cmd-hook obs-hook]]
    [yetibot.core.util.command :as command]
    [yetibot.core.interpreter :refer [*chat-source*]]
    [yetibot.core.parser :as parser]
    [yetibot.core.util.format :refer [remove-surrounding-quotes]]))

(def cli-options
  [["-e" "--event-type EVENT_TYPE"
    (str "Event type. Valid events are: " (s/join ", " (map name all-event-types)))
    :default "message"
    :validate [#(contains? all-event-types (keyword %))
               (str "Must be one of: " (s/join ", " (map name all-event-types)))]]
   ["-c" "--channel-pattern CHANNEL"
    "Channel(s) to fire this observer on. Accepts a regex that may match more
     than one channel"]
   ["-u" "--user-pattern USER" "Username pattern to trigger observer for"]])

(defn add-observer
  [observer-info]
  ;; always create - don't attempt to update
  (model/create observer-info)
  observer-info)

;; Use a single obs-hook to monitor all dynamic observers. That way when it's
;; removed from the database, it won't be checked here either.
(defn obs-handler [{:keys [body reaction user yetibot-user chat-source
                           thread-ts message-user event-type]
                    :as event-info}]
  (info "obs-handler" (color-str :blue (dissoc event-info :user :yetibot-user)))
  (let [observers (model/find-all)
        channel (:room chat-source)
        username (:username user)
        ;; when event type is react, message-username is the username of the
        ;; user that originally posted the message
        message-username (:username message-user)]
    ;; ignore commands on message types
    (when-not (and (= event-type :message)
                   (command/extract-command body))
      ;; (info "obs-handler" (color-str :blue event-info))
      ;; check all known observers from the db to see if any fired
      (doseq [observer observers]
        (let [event-type-matches? (= (:event-type event-info)
                                     (keyword (:event-type observer)))

              {:keys [user-pattern channel-pattern pattern cmd]}
              observer

              user-match? (or (nil? user-pattern)
                              (re-find (re-pattern user-pattern) username))

              channel-match? (or (nil? channel-pattern)
                                 (re-find (re-pattern channel-pattern) channel))

              ;; when matching on a :message event match against the body.
              ;; when matching on a :react event match against the reaction.
              ;; when matching on any other event types, match on the username
              match-text (condp = event-type
                           :message body
                           :react reaction
                           username)

              match? (and event-type-matches?
                          user-match?
                          channel-match?
                          (or (s/blank? pattern)
                              (re-find (re-pattern pattern) match-text)))]

          (when match?
            (go
              (info "obs matched"
                    (color-str
                     :yellow
                     (pr-str
                      {:match? match?
                       :*thread-ts* *thread-ts*
                       :thread-ts thread-ts
                       :user-match? user-match?
                       :channel-match? channel-match?
                       :channel channel
                       :blank-pattern? (s/blank? pattern)})))
              (binding [*chat-source* chat-source
                        *thread-ts* (or *thread-ts* thread-ts)]
                ;; apply templating to the cmd with selmer
                (let [rendered-cmd (render cmd {:username username
                                                :message-username
                                                message-username
                                                :reaction reaction
                                                :body body
                                                :channel channel})
                      expr (str command/config-prefix
                                (if body
                                  ;; older / existing behavior for piping matched
                                  ;; message into the observer's command
                                  rendered-cmd
                                  ;; new behavior for non-message type observers
                                  ;; uses template rendering to access the
                                  ;; username and channel name with no explicit
                                  ;; piping behavior
                                  rendered-cmd))
                      [{:keys [error? timeout? result] :as expr-result}]
                      (record-and-run-raw expr user yetibot-user)]
                  (if (and result (not error?) (not timeout?))
                    (chat-data-structure result)
                    (info "Skipping observer because it errored or timed out"
                          (pr-str expr-result))))))))))))

(defonce hook (obs-hook all-event-types #'obs-handler))

(defn- quote-command [cmd]
  ;; A copied definition must not run its pipes or subexpressions immediately.
  ;; Check the real grammar: a command containing quotes may need either style.
  (some (fn [quote]
          (let [quoted (str quote cmd quote)
                tree (parser/parser (str "echo " quoted))]
            (when (and (vector? tree) (= 2 (count tree))
                       (not-any? #(and (vector? %) (= :sub-expr (first %)))
                                 (tree-seq coll? seq tree)))
              quoted)))
        ["\"" "'"]))

(defn format-observer
  [{:keys [id user-id user-pattern channel-pattern event-type pattern cmd]}]
  (let [definition (str command/config-prefix "obs -e " event-type
                        (when user-pattern (str " -u " user-pattern))
                        (when channel-pattern (str " -c " channel-pattern))
                        (when-not (s/blank? pattern) (str " " pattern))
                        " = ")
        quoted (quote-command cmd)]
    (str (if quoted (str definition quoted)
             (str "[not copyable] " definition cmd))
         (when (or id user-id)
           (str "\n" (s/join " " (cond-> []
                                   id (conj (str "[id " id "]"))
                                   user-id
                                   (conj (str "[created by "
                                              user-id "]")))))))))

(defn wire-observer
  "Simply acknowledges that the observe was created or replaced. The wiring is
   done implicitly in obs-handler which pulls observers out of the db."
  [observer]
  (debug "wire-observer" (color-str :blue observer))
  (format "Created observer %s" (format-observer observer)))

(defn parse-observe-opts
  [opts-str]
  (parse-opts (map trim (split opts-str #" ")) cli-options))

(defn observe-cmd
  "observe <pattern> = <command> # run a command when an event matches

   `obs` and `observer` are aliases. Choose a type with `-e`; the default is
   `message`, so reactions need `-e react`. Patterns are case-sensitive regexes.

   Messages: match the message text (commands are ignored).
     !obs hello = echo Hello, {{username}}!

   Discord reactions: match the Unicode emoji or custom emoji name.
     !obs -e react 💬 = echo {{message-username}}: {{body}}

   Slack reactions: match the emoji name, not its picture or :colons:.
   Underscores become spaces: `speech_balloon` matches `speech balloon`.
     !obs -e react speech balloon = echo {{username}} reacted: {{body}}

   Joins and leaves: match the username, or omit the pattern to match anyone.
     !obs -e enter = echo Welcome, {{username}}!
     !obs -e leave = echo Goodbye, {{username}}!

   `-e sound` and `-e kick` also match usernames. Event support depends on the
   adapter; Discord currently emits message/react, not joins/leaves/sound/kick.

   Optional filters go before the pattern:
     -u <regex> restricts the triggering username (the reactor for reactions).
     -c <regex> restricts the channel (Slack name or Discord channel ID).
   Without -c, the observer can fire in any channel the bot receives events in.
     !obs -u ^alice$ -c ^general$ hello = echo Hi, Alice!

   Quote a command containing pipes or subexpressions so it runs on the event,
   not while creating the observer:
     !obs hello = \"echo {{body}} | upper\"

   Template variables:
     {{username}}: triggering user; for reactions, the person who reacted.
     {{body}}: message text; for reactions, the message being reacted to.
     {{reaction}}: reaction emoji/name (react events only).
     {{message-username}}: original message author (react events only).
     {{channel}}: Slack channel name or Discord channel/thread ID.

   List with !obs or !obs list. Each first line is a copyable creation command;
   its ID/creator are shown separately. Pasting creates another observer.
   Unsupported quote combinations are marked [not copyable], not evaluated.
   Remove an observer with !obs remove <id>.

   Observers run commands automatically. Use filters and avoid noisy loops."
  {:yb/cat #{:util}}
  [{[_ opts-str cmd] :match user :user}]
  (info "observer user" (pr-str user))
  (let [parsed-opts (parse-observe-opts opts-str)]
    (if-let [parse-errs (:errors parsed-opts)]
      (s/join " " parse-errs)
      (let [{:keys [event-type user-pattern channel-pattern]}
            (:options parsed-opts)

            pattern (when-let [args (->> parsed-opts :arguments seq)]
                      (s/join " " args))

            obs-info (cond-> {:user-id (:username user)
                              :event-type event-type
                              :pattern pattern
                              :cmd (remove-surrounding-quotes cmd)}
                       user-pattern (assoc :user-pattern user-pattern)
                       channel-pattern (assoc :channel-pattern channel-pattern))]
        (info "create observer" (color-str :blue (pr-str obs-info)))
        {:result/value ((comp wire-observer add-observer) obs-info)
         :result/data obs-info}))))

(defn list-observers
  "observe # list observers"
  {:yb/cat #{:util}}
  [& _]
  (if-let [observers (seq (model/find-all))]
    {:result/value (map format-observer observers)
     :result/data observers}
    {:result/error "No observers have been defined yet 🤔"}))

(defn remove-observers
  "observe remove <id> # remove observer by id"
  {:yb/cat #{:util}}
  [{[_ id] :match}]
  (let [id (read-string id)
        [status] (model/delete id)]
    (if (zero? status)
      (let [obs (seq (model/find-all))]
        {:result/error
         (if obs
           (format
             "Could not find observer with ID `%s`. Valid observers are: %s"
             id
             (s/join ", " (map #(str "`" (:id %) "`: " (:pattern %)) obs)))
           "There are no existing observers 😑")})
      (format "Observer `%s` removed" id))))

(defn load-observers []
  (run! wire-observer (model/find-all)))

(defonce loader (future (load-observers)))

(cmd-hook {"obs" #"obs"
           "observer" #"observer"}
  #"^(list)*$" list-observers
  #"remove\s+(\S+)" remove-observers
  #"(.+)\=\s*(.+)" observe-cmd)
