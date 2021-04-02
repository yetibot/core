(ns yetibot.core.interpreter
  "Handles evaluation of a parse tree"
  (:require
    [yetibot.core.chat :refer [suppress]]
    [yetibot.core.models.default-command :refer [fallback-enabled?
                                                 configured-default-command]]
    [yetibot.core.models.channel :as channel]
    [taoensso.timbre :refer [color-str debug info]]
    [yetibot.core.util.format :refer [pseudo-format to-coll-if-contains-newlines]]))

(def ^:dynamic *current-user*)
(def ^:dynamic *yetibot-user*)
(def ^:dynamic *chat-source*)
(def
  ^{:dynamic true
    :doc
    "Alternate source for data when data across a pipe is not provided. This
     allows a previous command to propagate data into a subsequent sub-command,
     e.g.:

     !scores | echo
       `render {{score}}` with weather
       `render {{zip}} | weather | render {{weather.description}}`"}
  *contextual-data* nil)

(or nil *contextual-data* 1)

(defn handle-cmd
  "Hooked entry point for all command handlers. If no handlers intercept, it
   falls back to image search when available."
  [cmd-with-args extra]
  (info "nothing handled" cmd-with-args
        \newline (pr-str extra))
  (if (fallback-enabled?)
    (if-not (:fallback? extra)
      (handle-cmd (str (configured-default-command) " " cmd-with-args)
                  (assoc extra :fallback? true))
      ;; if fallback? is true, nothing handled this command so don't try to
      ;; fallback again
      (format "I don't know how to handle %s" cmd-with-args))
    ;; return nothing when fallbacks are disabled
    (suppress {})))

(defn pipe-cmds
  "Pipe acc into cmd-with-args by either appending or sending acc as an extra
   :opts"
  [evaluator acc [cmd-ast & next-cmds]]
  (debug "pipe-cmds" *chat-source* acc cmd-ast next-cmds)
  (let [;; the previous accumulated value. for the first command in a series of
        ;; piped commands, preivous-value and previous-data will be empty
        {previous-value :value
         previous-data-collection :data-collection
         previous-data :data} acc
        extra {:raw previous-value
               :data (or previous-data *contextual-data* previous-value)
               :data-collection previous-data-collection
               :settings (:settings acc)
               :skip-next-n (:skip-next-n acc)
               :next-cmds next-cmds
               :user *current-user*
               :yetibot-user *yetibot-user*
               :chat-source *chat-source*}
        possible-opts (to-coll-if-contains-newlines previous-value)]

    (if (pos? @(:skip-next-n acc))
      (do
        (swap! (:skip-next-n acc) dec)
        (info
         (color-str :yellow
                    (str
                     "skipping already-consumed command" cmd-ast
                     "and the next"
                     @(:skip-next-n acc) "commands")))
        acc)

      ;; if possible-opts is a string, append acc to args. otherwise send
      ;; possible-opts as an extra :opts param and append nothing to
      ;; cmd-with-args.

      ;; bind data so that it "leaks" into evaluation of sub expressions
      (binding [*contextual-data* previous-data]
        (debug "piping with *contextual-data*" *contextual-data*)
        (let [cmd-with-args (evaluator cmd-ast)

             ;; the result of a commad handler can either be:
             ;; - the literal value itself
             ;; - a map containing a :value key and an optional :data key
             command-result
             (apply
              handle-cmd
              ;; determine whether to pass args to handle command as an :opts
              ;; collection or as a single value, depending on whether previous
              ;; value looks like a collection
              (if (coll? possible-opts)
                [cmd-with-args (conj extra {:opts possible-opts})]
                ;; value is the previous primitive output from the last
                ;; command. the first time around value is empty so just use
                ;; the raw cmd-with-args.
                [(if (empty? (str previous-value))
                   cmd-with-args
                   ;; next time apply pseudo-format to support %s substitution
                   (pseudo-format cmd-with-args (str previous-value)))
                 extra]))

             _ (info "command-result" (color-str :green (pr-str command-result)))

             {value :result/value
              error :result/error
              ;; collection-path is a path to a collection inside of `data` that
              ;; holds symmetry with opts - this is used to derive
              ;; `data-collection`
              collection-path :result/collection-path
              ;; data-collection is the piece of data inside of data - this is an
              ;; alternative to providing a `collection-path`
              data-collection :result/data-collection
              ;; the actual raw data (presumably from an API call)
              data :result/data} (when (map? command-result) command-result)

             ;; when `collection-path` is provided, obtain it and pass it over
             ;; the pipe for potential consumption by collection commands
             data-collection (or data-collection
                                 (when collection-path
                                   (get-in data collection-path))
                                 ;; or if it's not provided, check to see if data
                                 ;; is sequential and provide it as the
                                 ;; data-collection, which is used by collection
                                 ;; utilities like head, tail, and random
                                 (when (sequential? data) data))]

         (if error
           ;; if there's an error short circuit the pipeline using `reduced`
           (do
             (info "Caught error in pipeline" error)
             (reduced
              {:error
               (str "💥 Error in `" cmd-with-args "`: " error " 💥")}))
           ;; otherwise continue reducing
           (if (and (map? command-result) value)
             (assoc acc
                    :value value
                    :data-collection data-collection
                    :data data)
             (assoc acc :value command-result))))))))

(defn handle-expr
  "Called by the parser AST transformer with a partially-evaluated AST.

   We need to lazily transform each cmd within the AST so that the commands can
   influence subsequent commands (such as leak data into sub expressions).

   Entry point for Yetibot expression evaluation. An expression is the top-level
   construct. It contains 1 or more commands. Commands are reduced to a single
   output by sequentially piping the output from one command to the next until
   there are no more commands to evaluate.

   :data is an optional map that can contain any additional data from a previous
   command, to be optionally consumed by later commands."
  [evaluator cmds]
  (info "reduce commands" (prn-str cmds))
  ; look up the settings for channel in *chat-source*
  (let [channel-settings (channel/settings-for-chat-source *chat-source*)]
    (reduce
     (partial #'pipe-cmds evaluator)
     ;; Allow commands to consume n next commands in the pipeline and inform
     ;; the reducer to skip over them. This is useful e.g. queries that can be
     ;; optimized by "pushing down" the operating into the query engine.
     {:settings channel-settings
      :skip-next-n (atom 0)
      :value ""
      :data nil}
     ;; let each command peak into the next command so it can decide whether it
     ;; wants to consume it.
     (partition-all (count cmds) 1 cmds))))
