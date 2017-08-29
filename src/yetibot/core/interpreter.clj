(ns yetibot.core.interpreter
  "Handles evaluation of a parse tree"
  (:require
    [yetibot.core.models.default-command :refer [configured-default-command]]
    [clojure.set :refer [difference intersection]]
    [yetibot.core.models.room :as room]
    [taoensso.timbre :refer [debug info warn error]]
    [yetibot.core.util :refer [psuedo-format]]
    [yetibot.core.util.format :refer [to-coll-if-contains-newlines]]))

(def ^:dynamic *current-user*)
(def ^:dynamic *chat-source*)

(defn handle-cmd
  "Hooked entry point for all command handlers. If no handlers intercept, it
   falls back to image search when available."
  [cmd-with-args extra]
  (info "nothing handled" cmd-with-args)
  (if-not (:fallback? extra)
    (handle-cmd (str (configured-default-command) " " cmd-with-args)
                (assoc extra :fallback? true))
    ; if fallback? is true, nothing handled this command so don't try to
    ; fallback again
    (format "I don't know how to handle %s" cmd-with-args)))

(defn pipe-cmds
  "Pipe acc into cmd-with-args by either appending or sending acc as an extra
   :opts"
  [acc [cmd-with-args & next-cmds]]
  (debug "pipe-cmds" *chat-source* acc cmd-with-args next-cmds)
  (let [extra {:raw (:value acc)
               :settings (:settings acc)
               :skip-next-n (:skip-next-n acc)
               :next-cmds next-cmds
               :user *current-user*
               :chat-source *chat-source*}
        value (:value acc)
        possible-coll-acc (to-coll-if-contains-newlines value)]
    (if (> @(:skip-next-n acc) 0)
      (do
        (swap! (:skip-next-n acc) dec)
        (info "skipping already-consumed command" cmd-with-args "and the next"
              @(:skip-next-n acc) "commands")
        acc)
      ; if possible-coll-acc is a string, append acc to args. otherwise send
      ; possible-coll-acc as an extra :opts param and append nothing to
      ; cmd-with-args.
      (-> acc
          (update-in
            [:value]
            (fn [value]
              (apply
                handle-cmd
                ; determine whether to pass args to handle command as an :opts
                ; collection or as a single value.
                (if (coll? possible-coll-acc)
                  [cmd-with-args (conj extra {:opts possible-coll-acc})]
                  ; first time around value is empty so just use the raw
                  ; cmd-with-args
                  [(if (empty? value)
                     cmd-with-args
                     ; next time apply psuedo-format to support %s substitution
                     (psuedo-format cmd-with-args value))
                   extra]))))))))

(defn handle-expr
  "Entry point for Yetibot expression evaluation  An expression is the
   top-level construct. It contains 1 or more commands. Commands are reduced
   to a single output by sequentially piping the output from one command to the
   next until there are no more commands to evaluate."
  [& cmds]
  (info "reduce commands" (prn-str cmds) (partition-all (count cmds) 1 cmds))
  ; look up the settings for room in *chat-source*
  (let [room-settings (room/settings-for-chat-source *chat-source*)]
    (:value
      (reduce
        pipe-cmds
        ; Allow commands to consume n next commands in the pipeline and inform
        ; the reducer to skip over them. This is useful e.g. queries that can be
        ; optimized by "pushing down" the operating into the query engine.
        {:settings room-settings :skip-next-n (atom 0) :value ""}
        ; let each command peak into the next command so it can decide whether it
        ; wants to consume it.
        (partition-all (count cmds) 1 cmds)))))
