(ns yetibot.core.commands.observe
  (:require
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.db.observe :as model]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
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

(defn wire-observer
  [{:keys [event-type pattern cmd user]}]
  (let [existing (lookup pattern)
        pattern (re-pattern pattern)]
    (obs-hook #{(keyword event-type)}
              (fn [event-info]
                (let [body (:body event-info)]
                  (when (re-find pattern body)
                    (chat-data-structure
                      (handle-unparsed-expr
                        (format "echo %s | %s" body cmd)))))))
    (if existing
      (format "Replaced existing observer %s = %s" pattern (:cmd existing))
      (format "%s observer created" pattern))))

(defn observe-cmd
  "observe [event-type] <pattern> = <cmd> # setup an observer for <pattern>.
   When a match occurs, it'll be passed via normal pipe-semantics to <cmd>, e.g.
   echo <matched-text> | <cmd>. <cmd> may contain a piped expression, but it
   must be quoted.

   [event-type] is optional. If ommitted, it will default to \"message.\" Valid
   event types are: message, leave, enter, sound, kick.

   Examples:

   1. generate a meme when appropriate
   !observe y.+u.+no.* = meme y u no:

   2. lookup the temperature any time someone mentions something that looks like a zip code:
   !observe \\b\\d{5}\\b = \"weather | head 2 | tail\"
  "
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

(cmd-hook #"observe"
  #"^$" list-observers
  #"remove\s+(\S+)" remove-observers
  #"((\S+)\s+)*(\S+)\s*\=\s*(.+)" observe-cmd)
