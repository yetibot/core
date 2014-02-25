(ns yetibot.core.hooks
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.util :refer [with-fresh-db]]
    [yetibot.core.handler]
    [clojure.string :as s]
    [yetibot.core.interpreter :refer [handle-cmd]]
    [yetibot.core.models.help :as help]
    [robert.hooke :as rh]
    [clojure.stacktrace :as st]
    [clojure.contrib.cond :refer [cond-let]]))

(def ^:private Pattern java.util.regex.Pattern)

(defn suppress
  "Wraps parameter in meta data to indicate that it should not be posted to campfire"
  [data-structure]
  (with-meta data-structure {:suppress true}))

; Stores the mapping of prefix-regex -> sub-commands.
(defonce hooks (atom {}))

(defonce prefix->topic (atom {}))

(defn find-sub-cmds
  "Matches prefix against command regexes in `hooks.`"
  [prefix]
  (first (filter (fn [[k v]] (re-find k prefix)) @hooks)))

(defn cmd-unhook
  "Removes the sub-commands for a prefix / topic."
  [topic]
  (help/remove-docs topic)
  (swap! hooks dissoc topic))

(defn handle-with-hooked-cmds
  "Looks up the set of possible commands by matching the first word against
   prefixes stored in `hooks`. If it finds a match, it then matches against
   sub-commands and defaults to help if no sub-commands match.
   If unable to match prefix, it calls the callback, letting `handle-cmd`
   implement its own default behavior."
  [callback cmd-with-args {:keys [chat-source user opts] :as extra}]
  (let [[cmd args] (s/split cmd-with-args #"\s+" 2)
        args (or args "")] ; make it an empty string if no args
    (if-let [[cmd-re sub-cmds] (find-sub-cmds cmd)]
      ; Now try to find a matching sub-commands
      (let [cmd-pairs (partition 2 sub-cmds)]
        (info "found" cmd-re "on cmd" cmd "args:" args)
        (if-let [[match sub-fn] (some (fn [[sub-re sub-fn]]
                                        (info "some?" sub-re args)
                                        (when-let [match (re-find sub-re args)]
                                          [match sub-fn])) cmd-pairs)]
          (sub-fn (merge extra {:cmd cmd :args args :match match}))
          ; couldn't find any sub commands so default to help.
          (yetibot.core.handler/handle-unparsed-expr (str "help " (get @prefix->topic cmd-re)))))
      (callback cmd-with-args extra))))

; Hook the actual handle-cmd called during interpretation.
(rh/add-hook #'handle-cmd #'handle-with-hooked-cmds)

(defn cmd-hook-resolved
  "Expects fully resolved syntax where as plain cmd-hook can take normally
   unresolved symbols like _ and translate them into '_"
  [prefix & cmds]
  (let [[topic prefix] (if (vector? prefix) prefix [(str prefix) prefix])
        cmd-pairs (partition 2 cmds)]
    (swap! prefix->topic conj {prefix topic})
    (help/add-docs topic
                   (map (fn [[_ cmd-fn]] (:doc (meta cmd-fn)))
                        cmd-pairs))
    (swap! hooks conj {prefix cmds})))

(defmacro cmd-hook
  "Takes potentially special syntax and resolves it to symbols for
   cmd-hook-resolved. Currently _ is the only special syntax."
  [prefix & cmds]
  (let [cmd-pairs (partition 2 cmds)]
    `(cmd-hook-resolved
       ~prefix
       ~@(mapcat (fn [[k# v#]]
                   [(condp = k#
                      '_ #".*"
                      k#)
                    ; Need to resolve the var in order to get at its docstring.
                    ; This only applies to regular usage of cmd-hook. If using
                    ; cmd-hook with unresolvable anonymous functions (such as in
                    ; alias) the client must add the help metdata itself as
                    ; cmd-hook cannot extract it.
                    (if-let [resolved (resolve v#)] resolved v#)])
                 cmd-pairs))))

(defn obs-hook
  "Pass a collection of event-types you're interested in and an observer function
   that accepts a single arg. If an event occurs that matches the events in your
   event-types arg, your observer will be called with the event's json."
  [event-types observer]
  (rh/add-hook
    #'yetibot.core.handler/handle-raw
    (let [event-types (set event-types)]
      (fn [callback chat-source user event-type body]
        (when (contains? event-types event-type)
          (with-fresh-db
            (observer {:chat-source chat-source
                       :event-type event-type
                       :user user
                       :body body})))
        (callback chat-source user event-type body)))))
