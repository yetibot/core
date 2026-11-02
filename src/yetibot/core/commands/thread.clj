(ns yetibot.core.commands.thread
  (:require [yetibot.core.adapters.discord :as discord]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.interpreter :as interp]))

(defn thread-cmd
  "thread <command> # run a whole command expression in a new Discord thread
   echo hi | thread # send the piped result into a thread, without executing it
   echo hi | thread | echo result: %s # later pipe output stays threaded
   # Server text channels only. Edits/reruns reuse the confirmed message thread.
   # Direct-only controls still require standalone commands."
  [{:keys [raw data-collection]}]
  (let [route (discord/ensure-command-thread!)]
    (if-let [error (:error route)]
      {:result/error error}
      (if (map? raw)
        {:result/value raw :result/data interp/*contextual-data*
         :result/data-collection data-collection}
        raw))))

(cmd-hook #"thread" _ thread-cmd)
