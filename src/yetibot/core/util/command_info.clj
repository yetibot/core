(ns yetibot.core.util.command-info
  (:require
    [yetibot.core.hooks :as hooks]
    [yetibot.core.interpreter :as interpreter]
    [yetibot.core.models.help :as help]
    [yetibot.core.parser :refer [parse-and-eval transformer parser]]
    [taoensso.timbre :refer [color-str debug info]]))

(defn simple-command? [parsed-expr]
  (every?
    identity
    ;; simple expressions only have 2 items in the top level parse tree
    [(= 2 (count parsed-expr))
     ;; simple expressions only have 1 :expr
     (= 1 (->> parsed-expr
               flatten
               (filter (partial = :expr))
               count))]))

(defn command-execution-info
  "Obtain parsing results and a data structure representing the command and
   subcommand that was executed and optionally its result.

   Only supports single command expressions (no pipes or sub expressions).

   Required arguments:

   command - the simple command to parse

   An optional second map argument consisting of keys:

   :opts - the opts to pass the command (i.e. what would normally be passed from
           a previous command across a pipe)

   :data - data to pass in (i.e. would normally be passed from a previous command
           in a pipeline)

   :data-collection - the collection subset of data that has symmetry with :opts

   :run-command? - whether to actually run the command. By default the command
                   will not be run, since commands often have side effects and
                   thus would not be suitable for unit tests

   Returns a map of:

   :ast - the result of parsing the command
   :sub-commmands - list of all sub-commands for the top level command prefix
   :matched-sub-cmd - the single sub-command that this command actually matched
   :match - the result of passing the command args to the sub-command regex
            (which is how we determine `:matched-sub-cmd`)

   Useful for testing."
  [command & [{:keys [opts data-collection data raw run-command?]}]]
  (info "command-execution-info" opts)
  (let [parsed (parser command)]
    (if (simple-command? parsed)
      (let [[cmd args] (hooks/split-command-and-args command)
            [cmd-re sub-commands] (hooks/find-sub-cmds cmd)
            [match sub-fn] (hooks/match-sub-cmds args sub-commands)]
        (merge
         {:parse-tree parsed
          :sub-commands sub-commands
          :matched-sub-cmd sub-fn
          :match match
          :command cmd
          :command-args args}
         (when run-command?
           {:result (sub-fn {:match match
                             :args args
                             :data data
                             :data-collection data-collection
                             :raw raw
                             :opts opts})})))
      (throw (ex-info
              (str "Invalid command, only simple commands are supported")
              {:parsed parsed})))))
