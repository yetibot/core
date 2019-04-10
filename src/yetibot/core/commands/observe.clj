(ns yetibot.core.commands.observe
  (:require
    [clojure.core.async :refer [go]]
    [selmer.parser :refer [render]]
    [clojure.string :as s :refer [split trim]]
    [clojure.tools.cli :refer [parse-opts]]
    [taoensso.timbre :refer [color-str debug trace info warn error]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.db.observe :as model]
    [yetibot.core.handler :refer [record-and-run-raw all-event-types]]
    [yetibot.core.hooks :refer [cmd-hook obs-hook]]
    [yetibot.core.util :refer [is-command?]]
    [yetibot.core.util.command :as command]
    [yetibot.core.interpreter :refer [*chat-source*]]
    [yetibot.core.models.help :as help]
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
                           message-user event-type]
                    :as event-info}]
  (debug "obs-handler" (color-str :blue (dissoc event-info :user)))
  (let [observers (model/find-all)
        channel (:room chat-source)
        username (:username user)
        ;; when event type is react, message-username is the username of the
        ;; user that originally posted the message
        message-username (:username message-user)]
    ;; ignore commands on message types
    (when-not (and (= event-type :message)
                   (is-command? body))
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

          (debug "observer" (pr-str
                              {:match? match?
                               :user-match? user-match?
                               :channel-match? channel-match?
                               :channel channel
                               :blank-pattern? (s/blank? pattern)}))

          (when match?
            (go
              (binding [*chat-source* chat-source]
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

(defn format-observer
  [{:keys [id user-id user-pattern channel-pattern event-type pattern cmd]}]
  (str
    (if-not (s/blank? pattern) pattern (str "[any pattern]"))
    ": " cmd " "
    "[event type: " event-type "] "
    (when id (str "[id " id "] "))
    (when user-pattern (str "[user pattern: " user-pattern "]"))
    (when channel-pattern
      (str "[channel pattern: " channel-pattern "]"))
    (when user-id (str "[created by " user-id "]"))))

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
  "observe [-e event-type] [-u user-pattern] [-c channel-pattern] <pattern> = <cmd> # create an observer

   When a match occurs the <cmd> will be executed. <cmd> may contain a piped
   expression but it must be quoted. <cmd> can also include template variables:

     `{{channel}}` - the channel that the observer triggered in
     `{{username}}` - the username of the user that triggered the observer
     `{{body}}` - the body of the message that triggered the observer

   Only applies to `react` observer types:

     `{{reaction}}` - the reaction e.g. :rage:
     `{{message-username}}` - username that originally posted the messsage

   [event-type] is optional. If omitted, it will default to `message`.
   Valid event types are: `message`, `leave`, `enter`, `sound`, `kick`, `react`.

   [user-pattern] is an optional regex pattern. When specified, it creates an
   observer that only fires for a specific user or users that match the pattern.

   [channel-pattern] is an optional regex pattern. When specified it creates an
   observer that only fires for matching channel names.

   Examples:

   1. Generate a meme when appropriate
   !observe y.?u.?no = meme y u no:

   2. Lookup the temperature any time someone mentions something that looks like
   a zip code:
   !observe \\b\\d{5}\\b = \"weather | head 2 | tail\"

   3. Generate a rage meme with the body of the message any time someone reacts
   with a rage reaction (Slack only):
   !observe -e react rage = meme rage: {{body}}

   Observers can be easily abused. Use them with caution & restraint 🙏"
  {:yb/cat #{:util}}
  [{[_ opts-str cmd] :match user :user}]

  (info "observer user" (pr-str user))
  (let [parsed-opts (parse-observe-opts opts-str)]
    (if-let [parse-errs (:errors parsed-opts)]
      (s/join " " parse-errs)
      (let [{:keys [event-type user-pattern channel-pattern]}
            (:options parsed-opts)

            pattern (if-let [args (->> parsed-opts :arguments seq)]
                      (s/join " " args)
                      nil)

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
  (dorun (map wire-observer (model/find-all))))

(defonce loader (future (load-observers)))

(cmd-hook {"obs" #"obs"
           "observer" #"observer"}
  #"^(list)*$" list-observers
  #"remove\s+(\S+)" remove-observers
  #"(.+)\=\s*(.+)" observe-cmd)
