(ns yetibot.core.hooks
  (:require
    [clojure.set :refer [difference intersection]]
    [taoensso.timbre :refer [color-str trace debug info warn error]]
    [yetibot.core.handler]
    [clojure.string :as s]
    [yetibot.core.models.room :as r]
    [yetibot.core.interpreter :refer [handle-cmd]]
    [yetibot.core.models.help :as help]
    [robert.hooke :as rh]
    [clojure.stacktrace :as st]))

(def ^:private Pattern java.util.regex.Pattern)

(defn suppress
  "Wraps parameter in meta data to indicate that it should not be posted to chat"
  [data-structure]
  (with-meta data-structure {:suppress true}))

; Stores the mapping of prefix-regex -> sub-commands.
(defonce hooks (atom {}))

(defn cmds-for-cat
  "Return collection of vars for cmd handler functions whose :yb/cat meta
   includes `search-cat`"
  [search-cat]
  (let [search-cat (keyword search-cat)]
    (->> @hooks vals
         (mapcat
           ;; take every other, dropping the left side of the pair (i.e. the
           ;; regex that triggers the cmd)
           (comp (partial take-nth 2) rest))
         (filter
           (fn [cmd] ((set (:yb/cat (meta cmd))) search-cat)))
         ;; remove duplicates since multiple regexes can trigger the same
         ;; command
         set)))

(defonce re-prefix->topic (atom {}))

(defn find-sub-cmds
  "Given a top level command prefix look up corresponding sub-cmds by matching
   prefix against command regexes in `hooks.`"
  [prefix]
  (first (filter (fn [[k v]] (re-find (re-pattern k) prefix)) @hooks)))

(defn match-sub-cmds
  [command-args sub-cmds]
  (let [cmd-pairs (partition 2 sub-cmds)]
    (some (fn [[sub-re sub-fn]]
            (when-let [match (re-find sub-re command-args)]
              [match sub-fn]))
          cmd-pairs)))

(defn split-command-and-args
  [cmd-with-args]
  (let [[cmd args] (s/split cmd-with-args #"\s" 2)]
    ;; make args an empty string if no args
    [cmd (or args "")]))

(defn cmd-unhook
  "Removes the sub-commands for a prefix / topic."
  [topic]
  (let [str-re (str "^" topic "$")]
    (help/remove-docs topic)
    (swap! re-prefix->topic dissoc str-re)
    (swap! hooks dissoc str-re)))


(defn handle-with-hooked-cmds
  "Looks up the set of possible commands by matching the first word against
   prefixes stored in `hooks`. If it finds a match, it then matches against
   sub-commands and defaults to help if no sub-commands match.
   If unable to match prefix, it calls the callback, letting `handle-cmd`
   implement its own default behavior."
  [callback cmd-with-args {:keys [chat-source user opts settings] :as extra}]
  ;; (info "handle-with-hooked-cmds" extra)
  (let [[cmd args] (split-command-and-args cmd-with-args)]
    ;; find the top level command and its corresponding sub-cmds
    (if-let [[cmd-re sub-cmds] (find-sub-cmds cmd)]
      ;; Now try to find a matching sub-commands
      (if-let [[match sub-fn] (match-sub-cmds args sub-cmds)]
        ;; extract category settings
        (let [disabled-cats (set (r/cat-settings-key settings))
              fn-cats (set (:yb/cat (meta sub-fn)))]
          (if-let [matched-disabled-cats (seq (intersection disabled-cats fn-cats))]
            (str
              (s/join ", " (map name matched-disabled-cats))
              " commands are disabled in this channel🖐")
            (sub-fn (merge extra {:cmd cmd :args args :match match}))))
        ;; couldn't find any sub commands so default to help.
        (:value
          (yetibot.core.handler/handle-unparsed-expr
            (str "help " (get @re-prefix->topic (str cmd-re))))))
      (callback cmd-with-args extra))))

;; Hook the actual handle-cmd called during interpretation.
(rh/add-hook #'handle-cmd #'handle-with-hooked-cmds)

(defn lockdown-prefix-regex
  "Takes a regex and ensures that it's locked down with ^ or $ to prevent
   collisions with runtime aliases."
  [re]
  (let [re-str (str re)]
    (if (or (re-find #"^\^" re-str) (re-find #"\$$" re-str))
      re
      (re-pattern (str "^" re-str "$")))))

(defn cmd-hook-resolved
  "Expects fully resolved syntax where as plain cmd-hook can take normally
   unresolved symbols like _ and translate them into '_"
  [re-prefix & cmds]
  (let [[topic re-prefix] (if (vector? re-prefix) re-prefix [(str re-prefix) re-prefix])
        re-prefix (lockdown-prefix-regex re-prefix)
        cmd-pairs (partition 2 cmds)]
    (swap! re-prefix->topic conj {(str re-prefix) topic})
    (help/add-docs
      topic
      (map (fn [[_ cmd-fn]] (:doc (meta cmd-fn)))
           cmd-pairs))
    (swap! hooks conj {(str re-prefix) cmds})))

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
  "Pass a collection of event-types you're interested in and an observer
   function that accepts a single arg. If an event occurs that matches the
   events in your event-types arg, your observer will be called with the event's
   json."
  [event-types observer]
  (rh/add-hook
    #'yetibot.core.handler/handle-raw
    (let [event-types (set event-types)]
      (fn [callback chat-source user event-type yetibot-user
           {:keys [body reaction] :as event-info}]
        #_(debug "observed" (:yetibot? user)
               (color-str :blue chat-source)
               {:user user}
               {:event-type event-type}
               (color-str :blue (pr-str event-info)))
        (when (and
                (not (:yetibot? user))
                (contains? event-types event-type))
          (observer (merge event-info
                           {:chat-source chat-source
                            :event-type event-type
                            :user user
                            :yetibot-user yetibot-user})))
        ;; observers always pass through to the callback
        (callback chat-source user event-type yetibot-user event-info)))))
