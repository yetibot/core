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
   :sub-commmands - list of all sub-commands for the top level command prefix
   :matched-sub-cmd - the single sub-command that this command actually matched
   :match - the result of passing the command args to the sub-command regex
            (which is how we determine `:matched-sub-cmd`)

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
         :matched-sub-cmd sub-fn
         :match match
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
   :sub-commands (#"(\d+)" #'yetibot.core.commands.collections/head-n #".*" #'yetibot.core.commands.collections/head-1)
   :matched-sub-cmd [["2" "2"] #'yetibot.core.commands.collections/head-n]
   :command "head"
   :command-args "2"}

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
