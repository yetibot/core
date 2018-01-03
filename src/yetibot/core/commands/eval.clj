(ns yetibot.core.commands.eval
  (:require
    [schema.core :as s]
    [clojure.repl :refer :all]
    [clojure.pprint :refer [pprint]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.string :refer [split]]))

(defn- privs [] (:value (get-config [s/Str] [:eval :priv])))

(defn- user-is-allowed? [user]
  ((set (privs)) (:id user)))

(defn eval-cmd
  "eval <form> # evaluate the <form> data structure in Yetibot's context"
  {:yb/cat #{:util}}
  [{:keys [args user]}]
  (if (user-is-allowed? user)
    (with-out-str (pprint (eval (read-string args))))
    (format "You are not allowed, %s." (:name user))))

(cmd-hook #"eval"
          _ eval-cmd)
