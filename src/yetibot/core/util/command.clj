(ns yetibot.core.util.command
  (:require
   [clojure.spec.alpha :as s]
   [yetibot.core.config :refer [get-config]]
   [yetibot.core.models.help :as help]
   [yetibot.core.parser :refer [parser]]))

(s/def ::whitelist-config (s/coll-of string?))
(s/def ::blacklist-config (s/coll-of string?))

(defn pattern-config-set
  "Transform list of strings from config into a list of regex pattern"
  [spec path]
  (set
   (->>
    (get-config spec path)
    :value
    (map re-pattern))))

(defn whitelist []
  (pattern-config-set ::whitelist-config [:command :whitelist]))

(defn blacklist []
  (pattern-config-set ::blacklist-config [:command :blacklist]))

(defn throw-config-error! []
  (throw
   (ex-info
    "Invalid configuration: whitelist and blacklist cannot both be specified"
    {:whitelist whitelist
     :blacklist blacklist})))

;; check config and error on startup if invalid
(when (and (seq (whitelist)) (seq (blacklist)))
  (throw-config-error!))

(defn any-match? [patterns s]
  (some #(re-find % s) patterns))

(def always-enabled-commmands
  "The set of meta / foundational commands that should never be disabled via
   whitelist or blacklist.

   Note: a better way to control this list might be via yb/cat metadata on
   commands themselves."
  #{"help" "alias" "channel" "category"})

(defn command-enabled?
  "Given a command prefix, determine whether or not it is enabled.

   Users can specify either a whitelist collection of command patterns or a
   blacklist collection of patterns, but not both.

   If a whitelist is specified, all commands are disabled *except* those in the
   whitelist.

   If a blacklist is specified, all commands are enabled *except* those in the
   blacklist.

   Some foundation commands, the set of which is defined in
   `always-enabled-commmands`, are always enabled.

   Aliases are also always enabled as they simply compose other commands. If an
   alias attempts to compose a command that is not enabled, it would simply
   fallback depending on whether fallback is enabled (it is by default), and
   what the fallback command is (`help`, by default)."
  [command]
  (boolean
    (cond
      ;; exclude meta/foundational commands from black/white-lists
      (always-enabled-commmands command) true
      ;; exclude aliases from black/white-lists since they simply compose other
      ;; commands
      (@help/alias-docs command) true
      ;; blow up if both
      (and (seq (whitelist)) (seq (blacklist))) (throw-config-error!)
      ;; whitelist checking
      (seq (whitelist)) (any-match? (whitelist) command)
      ;; blacklist checking
      (seq (blacklist)) (not (any-match? (blacklist) command))
      ;; neither blacklist nor whitelist are configured
      :else true)))

(defn error?
  "Determine whether a value is an error map"
  [x]
  (and (map? x)
       (contains? x :result/error)))

(s/def ::prefix-config string?)

(def config-prefix
  (or (:value (get-config ::prefix-config [:command :prefix])) "!"))

(defn command?
  "Returns true if prefix matches a built-in command or alias"
  [prefix]
  (boolean (help/get-docs-for prefix)))

(defn extract-command
  "Returns the body if it has the command structure with the prefix;
   otherwise nil"
  ([body] (extract-command body config-prefix))
  ([body prefix]
    (re-find (re-pattern (str "^\\" prefix "(.+)")) body)))

(defn embedded-cmds
  "Parse a string and only return a collection of any embedded commands instead
   of the top level expression. Returns nil if there are none."
  [body]
  (->> (parser body)
       second second rest
       ; get expressions
       (filter #(= :sub-expr (first %)))
       (map second)
       (filter #(command? (-> % second second second)))))

