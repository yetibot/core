(ns yetibot.core.util.command-info
  (:require
    yetibot.core.commands.collections
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

   Returns a map of:

   :ast - the result of parsing the command
   :sub-commmands - list of sub-commands for the top level command
   :matched-sub-cmd - the individual sub-command that this commend matched

   Useful for testing."
  [command & [{:keys [opts ]}]]
  (info "command-execution-info" opts)
  (let [parsed (parser command)]
    (if (simple-command? parsed)
      (let [[cmd args] (hooks/split-command-and-args command)
            [cmd-re sub-commands] (hooks/find-sub-cmds cmd)
            [match sub-fn] (hooks/match-sub-cmds args sub-commands)]
        {:parse-tree parsed
         :sub-commands sub-commands
         :matched-sub-cmd [match sub-fn]
         :command cmd
         :command-args args
         })
      (throw (ex-info
               (str "Invalid command, only simple commands are supported")
               {:parsed parsed})))))

(comment

  (command-execution-info
    "echo hi")

  (command-execution-info
    "head 2" {:opts ["one" "two" "three"]})

  {:parse-tree [:expr [:cmd [:words "head" [:space " "] "2"]]]
   :sub-commands ["^head$" (#"(\d+)" #'yetibot.core.commands.collections/head-n #".*" #'yetibot.core.commands.collections/head-1)
                  ]}

  @hooks/hooks

  [:expr [:cmd [:words "words"]] [:cmd [:words "bar"]]]

  (get-in
    [:expr [:cmd [:words "words"]] [:cmd [:words "bar"]]]
    [1 1 1]
    )

  [:expr [:cmd [:words "bar"]]]

  (->> [:expr [:cmd [:words "foo" [:space " "] [:expr [:cmd [:words "bar"]]]]]]
       flatten
       (filter (partial = :expr))
       count
       )


  )
