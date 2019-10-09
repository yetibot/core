(ns yetibot.core.commands.cmd
  (:require
   [yetibot.core.hooks :refer [cmd-hook]]
   [yetibot.core.util.command :refer [extract-command config-prefix]]
   [yetibot.core.handler :refer [handle-unparsed-expr]]))

(def command-pattern (re-pattern (str "^" config-prefix)))

(defn strip-prefix
  "Strips command prefix from the beginning of a string, if present"
  [s]
  (if (re-find command-pattern s)
    (last (extract-command s))
    s))

(defn cmd
  "cmd # evaluate a literal command. Strips configured cmd prefix (default !) from the beginning if present"
  {:yb/cat #{:util}}
  [{match :match}]
  (:value (handle-unparsed-expr (strip-prefix match))))

(cmd-hook #"cmd"
          _ cmd)
