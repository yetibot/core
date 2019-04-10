(ns yetibot.core.commands.eval
  (:require
    [yetibot.core.models.admin :refer [user-is-admin?]]
    [clojure.repl :refer :all]
    [clojure.pprint :refer [*print-right-margin* pprint]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.string :refer [split]]))

(def disallow-gif "http://www.reactiongifs.com/wp-content/gallery/no/cowboy-shaking-head-no.gif")

(defn eval-cmd
  "eval <form> # evaluate the <form> data structure in Yetibot's context"
  {:yb/cat #{:util}}
  [{:keys [args user]}]
  (if (user-is-admin? user)
    (let [result (eval (read-string args))]
      (binding [*print-right-margin* 80]
        {:result/data result
         :result/value (with-out-str (pprint result))}))
    {:result/error
     (format "You are not allowed, %s %s" (:name user) disallow-gif)}))

(cmd-hook #"eval"
          _ eval-cmd)
