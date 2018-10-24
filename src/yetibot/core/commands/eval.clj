(ns yetibot.core.commands.eval
  (:require
    [schema.core :as s]
    [clojure.repl :refer :all]
    [clojure.pprint :refer [*print-right-margin* pprint]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.string :refer [split]]))

(defn- privs [] (set (:value (get-config [s/Str] [:eval :priv]))))

(defn- user-is-allowed? [user]
  ((privs) (:id user)))

(def disallow-gif "http://www.reactiongifs.com/wp-content/gallery/no/cowboy-shaking-head-no.gif")

(defn eval-cmd
  "eval <form> # evaluate the <form> data structure in Yetibot's context"
  {:yb/cat #{:util}}
  [{:keys [args user]}]
  (if (user-is-allowed? user)
    (binding [*print-right-margin* 80]
      (with-out-str (pprint (eval (read-string args)))))
    {:result/error
     (format "You are not allowed, %s %s" (:name user) disallow-gif)}))

(cmd-hook #"eval"
          _ eval-cmd)
