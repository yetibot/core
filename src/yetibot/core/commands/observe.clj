(ns yetibot.core.commands.observe
  (:require
    [selmer.parser :refer [render]]
    [clojure.string :as s :refer [split trim]]
    [clojure.tools.cli :refer [parse-opts]]
    [taoensso.timbre :refer [color-str debug trace info warn error]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.db.observe :as model]
    [yetibot.core.handler :refer [handle-unparsed-expr all-event-types]]
    [yetibot.core.hooks :refer [cmd-hook obs-hook]]
    [yetibot.core.util :refer [is-command?]]
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
(defn obs-handler [{:keys [body user chat-source event-type] :as event-info}]
  (info "obs-handler" (color-str :blue event-info))
  (let [observers (model/find-all)
        channel (:room chat-source)
        username (:username user)]
    (when-not (is-command? body) ;; ignore commands
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

              ;; when matching on a :message event match against the body. when
              ;; matching on any other event types, match on the username
              body-or-username (if (= :message event-type) body username)

              match? (and event-type-matches?
                          user-match?
                          channel-match?
                          (or (s/blank? pattern)
                              (re-find (re-pattern pattern) body-or-username)))]

          (trace "observer" (pr-str
                              {:match? match?
                               :user-match? user-match?
                               :channel-match? channel-match?
                               :channel channel
                               :blank-pattern? (s/blank? pattern)}))

          (when match?
            (future
              (let [rendered-cmd (render cmd {:username username
                                              :channel channel})
                    expr (if body
                           ;; older / existing behavior for piping matched message
                           ;; into the observer's command
                           (format "echo %s | %s" body rendered-cmd)
                           ;; new behavior for non-message type observers uses
                           ;; template rendering to access the username and
                           ;; channel name with no explicit piping behavior
                           rendered-cmd)
                    result (try
                             (handle-unparsed-expr chat-source user expr)
                             (catch Throwable e
                               (info "Error handling observer" expr e)
                               nil))]
                (when result
                  (info "obs expr" expr)
                  (chat-data-structure result))))))))))

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

   When a match occurs, it'll be passed via normal pipe-semantics to <cmd>, e.g.
   echo <matched-text> | <cmd>. <cmd> may contain a piped expression, but it
   must be quoted.

   Template variables are available in the <cmd> RHS:

     {{channel}} - the channel that the observer triggered in
     {{username}} - the username of the user that triggered the observer

   [event-type] is optional. If omitted, it will default to `message`. Valid
   event types are: `message`, `leave`, `enter`, `sound`, `kick`.

   [user-pattern] is an optional regex pattern. When specified, it creates an
   observer that only fires for a specific user or users that match the pattern.

   [channel-pattern] is an optional regex pattern. When specified it creates an
   observer that only fires for matching channel names.

   Examples:
   1. Generate a meme when appropriate
   !observe y.?u.?no = meme y u no:

   2. Lookup the temperature any time someone mentions something that looks like
   a zip code: !observe \\b\\d{5}\\b = \"weather | head 2 | tail\"

   Observers can be easily abused. Use them with caution & restraint 🙏."
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
        ((comp wire-observer add-observer) obs-info)))))

(defn list-observers
  "observe # list observers"
  {:yb/cat #{:util}}
  [& _]
  (if-let [observers (seq (model/find-all))]
    (map format-observer observers)
    "No observers have been defined yet 🤔"))

(defn remove-observers
  "observe remove <id> # remove observer by id"
  {:yb/cat #{:util}}
  [{[_ id] :match}]
  (let [id (read-string id)
        [status] (model/delete id)]
    (if (zero? status)
      (into
        [(format "Could not remove observer %s. Are you sure it exists?" id)
         ""]
        (list-observers))
      (format "Observer `%s` removed" id))))

(defn load-observers []
  (dorun (map wire-observer (model/find-all))))

(defonce loader (future (load-observers)))

(cmd-hook ["observe" #"^obs(erver*)*$"]
  #"^(list)*$" list-observers
  #"remove\s+(\S+)" remove-observers
  #"(.+)\=\s*(.+)" observe-cmd)
