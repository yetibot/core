(ns yetibot.core.commands.observe
  (:require
    [clojure.string :as s :refer [split trim]]
    [clojure.tools.cli :refer [parse-opts]]
    [taoensso.timbre :refer [color-str debug info warn error]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.db.observe :as model]
    [yetibot.core.handler :refer [handle-unparsed-expr all-event-types]]
    [yetibot.core.hooks :refer [cmd-hook obs-hook]]
    [yetibot.core.util :refer [is-command?]]
    [yetibot.core.interpreter :refer [*chat-source*]]
    [yetibot.core.models.help :as help]
    [yetibot.core.util :refer [with-fresh-db]]
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

(defn lookup [pattern] (model/find-first {:pattern pattern}))

(defn add-observer
  [observer-info]
  (if-let [existing (lookup (:pattern observer-info))]
    (model/update (:id existing) observer-info)
    (model/create observer-info))
  observer-info)

;; Use a single obs-hook to monitor all dynamic observers. That way when it's
;; removed from the database, it won't be checked here either.
(defn obs-handler [{:keys [body user chat-source event-type] :as event-info}]
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

              match? (and event-type-matches?
                          user-match?
                          channel-match?
                          (re-find (re-pattern pattern) body))]
          (when match?
            (let [expr (format "echo %s | %s" body cmd)]
              (debug "expr:" expr)
              (chat-data-structure (handle-unparsed-expr chat-source user expr)))))))))

(defonce hook (obs-hook all-event-types #'obs-handler))

(defn wire-observer
  "Simply acknowledges that the observe was created or replaced. The wiring is
   done implicitly in obs-handler which pulls observers out of the db."
  [{:keys [event-type pattern cmd user] :as observer}]
  (debug "wire-observer" (color-str :blue observer))
  (let [existing (lookup pattern)
        re (re-pattern pattern)]
    (if existing
      (format "Replaced existing observer %s = %s" pattern (:cmd existing))
      (format "%s observer created" pattern))))

(defn parse-observe-opts
  [opts-str]
  (parse-opts (map trim (split opts-str #" ")) cli-options))

(defn observe-cmd
  "observe [-e event-type] [-u user-pattern] [-c channel-pattern] <pattern> = <cmd>

   When a match occurs, it'll be passed via normal pipe-semantics to <cmd>, e.g.
   echo <matched-text> | <cmd>. <cmd> may contain a piped expression, but it
   must be quoted.

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
  (let [parsed-opts (parse-observe-opts opts-str)]
    (if-let [parse-errs (:errors parsed-opts)]
      (s/join " " parse-errs)
      (let [{:keys [event-type user-pattern channel-pattern]}
            (:options parsed-opts)

            pattern (->> parsed-opts :arguments (s/join " "))

            obs-info (cond-> {:user-id (:id user)
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
  [_]
  (map (fn [{:keys [user-pattern channel-pattern event-type pattern cmd]}]
         (str
           pattern ": " cmd " "
           "[event type: " event-type "] "
           (when user-pattern (str "[user pattern: " user-pattern "]"))
           (when channel-pattern
             (str "[channel pattern: " channel-pattern "]"))))
       (model/find-all)))

(defn remove-observers
  "observe remove <pattern> # remove observer by pattern"
  {:yb/cat #{:util}}
  [{[_ pattern] :match}]
  (model/delete-all {:pattern pattern})
  (format "observer `%s` removed" pattern))

(defn load-observers []
  (dorun (map wire-observer (model/find-all))))

(defonce loader (future (with-fresh-db (load-observers))))

(cmd-hook ["observe" #"^observer*$"]
  #"^(list)*$" list-observers
  #"remove\s+(\S+)" remove-observers
  #"(.+)\=\s*(.+)" observe-cmd)
