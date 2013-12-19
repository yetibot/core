(ns yetibot.core.version)

(def version (-> (slurp "project.clj") read-string (nth 2)))
