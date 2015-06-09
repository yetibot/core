(ns yetibot.core.config
  "Config is stored in an edn file. The config data structure maps to the
   namespaces of the code that depends on the config."
  (:require
    [clojure.java.io :refer [as-file]]
    [taoensso.timbre :refer [info warn error]]
    [clojure.pprint :refer [pprint]]
    [clojure.edn :as edn]
    [clojure.string :refer [blank? split]]))


;; TODO: is there a Clojure lens lib that could make accessing and getting
;; updates from config easier and more idiomatic?

(def config-path "config/config.edn")

(defn config-exists? [] (.exists (as-file config-path)))

(defonce ^:private config (atom nil))

(defn- load-edn [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception e
      (warn "Failed loading config: " e)
      {:yetibot {}})))

(defn reload-config
  ([] (reload-config config-path))
  ([path]
    (let [new-conf (load-edn path)]
      (info "☐ Try loading config at" path)
      (reset! config new-conf)
      (when-not (empty? (:yetibot @config))
        (info "☑ Config loaded"))
      new-conf)))

(defn get-config
  [& path]
  (let [path (if (coll? path) path [path])]
    (get-in @config path)))

(defn write-config! []
  (if (config-exists?)
    (spit config-path (with-out-str (pprint @config)))
    (warn config-path "file doesn't exist, skipped write")))

(defn update-config
  "Updates the config data structure and write it to disk."
  [& path-and-val]
  (let [path (butlast path-and-val)
        value (last path-and-val)]
    (swap! config update-in path (constantly value))
    (write-config!)))

(defn remove-config
  "Remove config at path and write it to disk."
  [& fullpath]
  (let [path (butlast fullpath)
        k (last fullpath)]
    (swap! config update-in path dissoc k))
  (write-config!))


(defn config-for-ns []
  (apply get-config (map keyword (split (str *ns*) #"\."))))

(defn conf-valid?
  ([] (conf-valid? (config-for-ns)))
  ([c]
   (and c
        (every? (complement (comp blank? str)) (vals c)))))

(defonce load-conf (reload-config))
