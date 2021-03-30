(ns yetibot.core.models.default-command
  "Determine which command to fallback to and whether fallback is enabled"
  (:require [clojure.spec.alpha :as s]
            [yetibot.core.config :refer [get-config]]
            [clojure.string :refer [blank?]]))

(s/def ::config any?)

(defn configured-default-command
  "Gets the default command, as defined by the instance config.
   Arity/2 allows for passing in custom config to compare against,
   mainly used for testing."
  ([] (configured-default-command (get-config ::config
                                              [:default :command])))
  ([cfg] (get cfg :value "help")))

(comment
  (configured-default-command {:value "findme"})
  (configured-default-command {})
  )

(s/def ::text string?)

(defn fallback-help-text-override
  "Optional config for fallback help text. May be nil. Arity/2 allows for passing
   in custom config to compare against, mainly used for testing."
  ([] (fallback-help-text-override (get-config ::text
                                               [:command :fallback :help :text])))
  ([cfg] (:value cfg)))

(s/def ::fallback-commands-enabled-config string?)

(defn fallback-enabled?
  "Determine whether fallback commands are enabled when user enters a command
   that doesn't exist. Default is true.
   In the future this may be channel specific but for now it is global.
   Arity/2 allows for passing in custom config to compare against, mainly used
   for testing."
  ([] (fallback-enabled? (get-config ::fallback-commands-enabled-config
                                     [:command :fallback :enabled])))
  ([cfg] (let [value (:value cfg)]
           (if-not (blank? value)
             (not (= "false" value))
             ;; enabled by default
             true))))

(comment
  (fallback-enabled? {:value "false"})
  (fallback-enabled? {:value false})
  (fallback-enabled? {:value "true"})
  (fallback-enabled? {:value "thiswilldefaulttotrue"})
  )
