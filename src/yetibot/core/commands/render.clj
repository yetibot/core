(ns yetibot.core.commands.render
  (:require
    [clojure.string :as string]
    [yetibot.core.hooks :refer [cmd-hook]]
    [taoensso.timbre :refer [debug info warn error]]
    [selmer.parser :refer [render]]))

(defn render-cmd
  "render <template> # renders template against data passed over a pipe"
  [{data :data match :match raw :raw :as args}]
  (info "render-cmd" match raw)
  (try
    (let [template (if raw
                     (string/replace match raw "") ;; remove piped args
                     match)
          rendered (if (sequential? data)
                     (map (partial render template) data)
                     (render template data))]
      {:result/data {:data data :template template}
       :result/value rendered})
    (catch Exception e
      (info "Error rendering template")
      (debug e)
      {:result/error (.getMessage e)})))

(cmd-hook #"render"
  #".+" render-cmd)
