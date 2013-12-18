(ns yetibot.core.commands.eval
  (:require
    [clojure.repl :refer :all]
    [clojure.pprint :refer [pprint]]
    [yetibot.core.config :refer [config-for-ns]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.string :refer [split]]))

(def ^:private privs (:privs (config-for-ns)))

(defn- user-is-allowed? [user]
  (boolean (some #{(:id user)} privs)))

(defn eval-cmd
  "eval <form> # evaluate the <form> data structure in YetiBot's context"
  [{:keys [args user]}]
  (if (user-is-allowed? user)
    (pr-str (eval (read-string args)))
    (format "You are not allowed, %s." (:name user))))

(cmd-hook #"eval"
          _ eval-cmd)
