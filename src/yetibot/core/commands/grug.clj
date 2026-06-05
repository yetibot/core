(ns yetibot.core.commands.grug
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]))

(def quotes
  ["grug have love/hate relationship with test: test save grug many, many uncountable time and grug love and respect test"
   "note, this good engineering advice but bad career advice: \"yes\" is magic word for more shiney rock and put in charge of large tribe of developer"
   "complexity very, very bad. given choice between complexity or one-on-one against T-Rex, grug take T-Rex: at least grug see T-Rex"
   "fear of looking dumb key tool of complexity demon"
   "dry is good, but dry can be early, brittle abstraction. copy-paste often better than bad abstraction"
   "log everything. logging is grug best friend"])

(defn grug-cmd
  "grug # show a random quote from the grug brained developer"
  {:yb/cat #{:fun}}
  [_]
  (rand-nth quotes))

(cmd-hook #"grug"
          _ grug-cmd)
