(ns yetibot.core.commands.thread
  (:require [yetibot.core.hooks :refer [cmd-hook]]))

(defn thread-cmd
  "thread <command> # run a whole command expression in a new Discord thread
   thread echo hello | echo result: %s # the entire pipe runs in the thread
   # Send directly in a Discord server text channel. Requires thread permissions.
   # Edits and confirmed reruns reuse the same thread. DMs and nested threads
   # are unsupported. Direct-only controls still require standalone commands."
  [_]
  {:result/error
   "Send thread <command> directly in a Discord server text channel, not through an alias, pipe or embedded command."})

(cmd-hook #"thread" _ thread-cmd)
