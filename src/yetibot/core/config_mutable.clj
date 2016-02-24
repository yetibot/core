(ns yetibot.core.config-mutable
  "Mutable config is stored in an edn file which may be updated at runtime."
  (:require
    [yetibot.core.util.config :as uc]
    [clojure.java.io :refer [as-file]]
    [taoensso.timbre :refer [info warn error]]
    [clojure.pprint :refer [pprint]]
    [clojure.edn :as edn]
    [clojure.string :refer [blank? split]]))

(def config-path (.getAbsolutePath (as-file "config/config.edn")))

(defn config-exists? [] (.exists (as-file config-path)))

(defonce ^:private config (atom {}))

(defn- load-edn! [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception e
      (error "Failed loading config: " e)
      nil)))

(defn reload-config!
  ([] (reload-config! config-path))
  ([path]
   (info "☐ Try loading config at" path)
   (let [new-conf (load-edn! path)]
     (reset! config new-conf)
     (when new-conf (info "☑ Config loaded"))
     new-conf)))

(defn write-config! []
  (if (config-exists?)
    (spit config-path (with-out-str (pprint @config)))
    (warn config-path "file doesn't exist, skipped write")))

(def apply-config-lock (Object.))

(defn apply-config
  "Takes a function to apply to the current value of a config at path"
  [path f]
  (locking apply-config-lock
    (swap! config update-in path f)
    (write-config!)))

(defn update-config
  "Updates the config data structure and write it to disk."
  [& path-and-val]
  (let [path (butlast path-and-val)
        value (last path-and-val)]
    (apply-config path (constantly value))))

(defn remove-config
  "Remove config at path and write it to disk."
  [& fullpath]
  (let [path (butlast fullpath)
        k (last fullpath)]
    (swap! config update-in path dissoc k))
  (write-config!))

(def get-config (partial uc/get-config @config))
