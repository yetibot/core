(ns yetibot.core.commands.decode
  (:require [yetibot.core.hooks :refer [cmd-hook]])
  (:import org.apache.commons.lang3.StringEscapeUtils))

(defn decode [s]
  (org.apache.commons.lang3.StringEscapeUtils/unescapeHtml4 s))

(defn decode-cmd
  "decode <string> # decode HTML entities in <string> using StringEscapeUtils"
  {:yb/cat #{:util :collection}}
  [{:keys [data data-collection opts args]}]
  {:result/value (if opts
                   (map decode opts)
                   (decode args))
   :result/data data
   :result/data-collection data-collection})

(cmd-hook #"decode"
  _ decode-cmd)
