(ns yetibot.core.commands.render
  (:require
    [clojure.string :as string]
    [yetibot.core.hooks :refer [cmd-hook]]
    [taoensso.timbre :refer [debug info warn error]]
    selmer.util
    [selmer.parser :refer [render]]))

;; configure html unescaping in Selmer:
(selmer.util/turn-off-escaping!)

;; alternatively, we could install a filter to unescape:
(comment
  (org.apache.commons.lang3.StringEscapeUtils/unescapeHtml4
    "Want to see some yetis? Come check out Trevor Hartman&#39;s talk &quot;Growing a Chatops Platform and Having Fun with Clojure&quot; where we take a look at the development of Yetibot! #clojure #clojurenorth – @clojurenorth Tue Feb 12 12:36:46 +0000 2019")
  )

(defn render-cmd
  "render <template> # renders a Selmer template against data passed over a pipe.

   See https://github.com/yogthos/Selmer for docs on templating."
  [{data :data match :match raw :raw :as args}]
  (info "render-cmd" match raw)
  (try
    (let [template (if (and raw (not (coll? raw)))
                     (string/replace match raw "") ;; remove piped args
                     match)
          rendered (if (sequential? data)
                     (map (partial render template) data)
                     (render template data))]
      {:result/data {:data data :template template}
       :result/value rendered})
    (catch Exception e
      (info "Error rendering template")
      ;; (debug e)
      {:result/error (.getMessage e)})))

(cmd-hook #"render"
  #".+" render-cmd)
