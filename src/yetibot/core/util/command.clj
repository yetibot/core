(ns yetibot.core.util.command
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.models.help :as help]
    [yetibot.core.parser :refer [parser]]))

(s/def ::whitelist-config (s/coll-of string?))
(s/def ::blacklist-config (s/coll-of string?))

(defn pattern-config-set
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

(defn command-enabled?
  "Given a command prefix, determine whether or not it is enabled.

   Users can specify either a whitelist collection of command patterns or a
   blacklist collection of patterns, but not both.

   If a whitelist is specified, all commands are disabled *except* those in the
   whitelist.

   If a blacklist is specified, all commands are enabled *except* those in the
   blacklist."
  [command]
  (boolean
   (cond
     (and (seq (whitelist)) (seq (blacklist)))
     (throw
      (ex-info
       "Invalid configuration: whitelist and blacklist cannot both be specified"
       {:whitelist whitelist
        :blacklist blacklist}))
     (seq (whitelist)) (any-match? (whitelist) command)
     (seq (blacklist)) (not (any-match? (blacklist) command))
     ;; neither blacklist nor hitelist are configured
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

