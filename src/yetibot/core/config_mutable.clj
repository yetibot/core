(ns yetibot.core.config-mutable
  "Mutable config is stored in an edn file which may be updated at runtime."
  (:require
    [yetibot.core.util.config :as uc]
    [environ.core :refer [env]]
    [clojure.java.io :refer [as-file]]
    [taoensso.timbre :refer [info warn error]]
    [clojure.pprint :refer [pprint]]
    [clojure.string :refer [blank? split]]))

(defn config-path []
  (if-let [path (env :config-mutable)]
    path
    (.getAbsolutePath (as-file "config-mutable.edn"))))

(defonce ^:private config (atom {}))

(defn reload-config!
  ([] (reload-config! (config-path)))
  ([path]
   (info "☐ Try loading config at" path)
   (let [new-conf (uc/load-edn! path)]
     (reset! config new-conf)
     (when new-conf (info "☑ Config loaded"))
     new-conf)))

(defn write-config! [path]
  (if (uc/config-exists? path)
    (spit path (with-out-str (pprint @config)))
    (warn path "file doesn't exist, skipped write")))

(def apply-config-lock (Object.))

(defn apply-config!
  "Takes a function to apply to the current value of a config at path"
  ([path f] (apply-config! (config-path) path f))
  ([file-path path f]
   (locking apply-config-lock
     (swap! config update-in path f)
     (write-config! file-path))))

(defn update-config!
  "Updates the config data structure and write it to disk."
  ([path value] (update-config! (config-path) path value))
  ([file-path path value]
   (apply-config! file-path path (constantly value))))

(def remove-config-lock (Object.))

(defn remove-config!
  "Remove config at path and write it to disk."
  ([fullpath] (remove-config! (config-path) fullpath))
  ([file-path fullpath]
   (locking remove-config-lock
     (let [path (butlast fullpath)
           k (last fullpath)]
       (swap! config update-in path dissoc k))
     (write-config! file-path))))

(defn get-config
  [schema path]
  (uc/get-config @config schema path))
