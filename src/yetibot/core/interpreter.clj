(ns yetibot.core.interpreter
  "Handles evaluation of a parse tree"
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.util :refer [psuedo-format]]
    [yetibot.core.util.format :refer [to-coll-if-contains-newlines]]))

(def ^:dynamic *current-user*)
(def ^:dynamic *chat-source*)

(defn handle-cmd
  "Hooked entry point for all command handlers. If no handlers intercept, it falls
   back to image search when available."
  [cmd-with-args extra]
  (info "nothing handled" cmd-with-args)
  ; default to looking up a random result from google image search
  (if (find-ns 'yetibot.core.commands.image-search)
    (handle-cmd (str "image " cmd-with-args) extra)
    (format "I don't know how to handle %s" cmd-with-args)))

(defn pipe-cmds
  "Pipe acc into cmd-with-args by either appending or sending acc as an extra
   :opts"
  [acc [cmd-with-args & next-cmds]]
  (info "pipe-cmds" acc cmd-with-args next-cmds)
  (let [extra {:raw (:value acc)
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
                (if (coll? possible-coll-acc)
                  [cmd-with-args (conj extra {:opts possible-coll-acc})]
                  [(if (empty? value) cmd-with-args (psuedo-format cmd-with-args value))
                   extra]))))))))

(defn handle-expr [& cmds]
  (info "reduce commands" (prn-str cmds) (partition-all (count cmds) 1 cmds))
  (:value
    (reduce
      pipe-cmds
      ; allow commands to consume n next commands in the pipeline and tell
      ; the reducer to skip over them
      {:skip-next-n (atom 0)
       :value ""}
      (partition-all (count cmds) 1 cmds))))
