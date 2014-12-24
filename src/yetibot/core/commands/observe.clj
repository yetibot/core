(ns yetibot.core.commands.observe
  (:require
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.db.observe :as model]
    [yetibot.core.handler :refer [handle-unparsed-expr all-event-types]]
    [yetibot.core.hooks :refer [cmd-hook obs-hook]]
    [yetibot.core.models.help :as help]
    [yetibot.core.util :refer [with-fresh-db]]
    [yetibot.core.util.format :refer [remove-surrounding-quotes]]))

(defn lookup [pattern] (model/find-first {:pattern pattern}))

(defn add-observer
  [observer-info]
  (if-let [existing (lookup (:pattern observer-info))]
    (model/update (:id existing) observer-info)
    (model/create observer-info))
  observer-info)

;; Use a single obs-hook to monitor all dynamic observers. That way when it's
;; removed from the database, it won't be checked here either.
(defn obs-handler [event-info]
  (info "obs-handler:" event-info)
  (let [observers (model/find-all)
        body (:body event-info)]
    ;; check all known observers from the db to see if any fired
    (doseq [observer observers]
      (info "check:" observer event-info)
      (let [event-type-matches? (= (:event-type event-info)
                                   (keyword (:event-type observer)))
            match? (and event-type-matches?
                        (re-find (re-pattern (:pattern observer)) body))]
        (when match?
          (chat-data-structure
            (handle-unparsed-expr
              (format "echo %s | %s" body (:cmd observer)))))))))

;; TODO: now the problem is the resulting command from a dynamic observer is
;; being observed, because it goes through  ... ????

(defonce hook (obs-hook all-event-types #'obs-handler))


(defn wire-observer
  [{:keys [event-type pattern cmd user]}]
  (let [existing (lookup pattern)
        re (re-pattern pattern)]
    (if existing
      (format "Replaced existing observer %s = %s" pattern (:cmd existing))
      (format "%s observer created" pattern))))

(defn observe-cmd
  "observe [event-type] <pattern> = <cmd> # setup an observer for <pattern>.
   When a match occurs, it'll be passed via normal pipe-semantics to <cmd>, e.g.
   echo <matched-text> | <cmd>. <cmd> may contain a piped expression, but it
   must be quoted.

   [event-type] is optional. If omitted, it will default to \"message.\" Valid
   event types are: message, leave, enter, sound, kick.

   Examples:
   1. Generate a meme when appropriate
   !observe y.+u.+no.* = meme y u no:

   2. Lookup the temperature any time someone mentions something that looks like a zip code:
   !observe \\b\\d{5}\\b = \"weather | head 2 | tail\"

   Observers can be easily abused. Use them with caution & restraint."
  [{[_ _ event-type pattern cmd] :match user :user}]
  (let [event-type (or event-type "message")]
    (info "create observer" event-type pattern cmd user)
    ((comp wire-observer add-observer)
     {:user-id (:id user)
      :event-type event-type
      :pattern pattern
      :cmd (remove-surrounding-quotes cmd)})))

(defn list-observers
  "observe # list observers"
  [_]
  (into {} (map (juxt :pattern :cmd) (model/find-all))))

(defn remove-observers
  "observe remove <pattern> # remove observer by pattern"
  [{[_ pattern] :match}]
  (model/delete-all {:pattern pattern})
  (format "observer `%s` removed" pattern))

(defn load-observers []
  (dorun (map wire-observer (model/find-all))))

(defonce loader (future (with-fresh-db (load-observers))))

(cmd-hook ["observe" #"^observer*$"]
  #"^(list)*$" list-observers
  #"remove\s+(\S+)" remove-observers
  #"((\S+)\s+)*(\S+)\s*\=\s*(.+)" observe-cmd)
