(ns yetibot.core.commands.ssh
  (:require
    [clojure.spec.alpha :as s]
    [taoensso.timbre :refer [info warn error]]
    [clj-ssh.ssh :refer [ssh ssh-agent add-identity session with-connection]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.config :refer [get-config]]))

(s/def ::host string?)

(s/def ::name string?)

(s/def ::server (s/keys :req-un [::host ::name]))

(s/def ::servers (s/coll-of ::server :kind vector?))

(s/def ::user string?)

(s/def ::key string?)

(s/def ::group (s/keys :req-un [::user ::servers ::key]))

(s/def ::groups (s/coll-of ::group :kind vector?))

(s/def ::config (s/keys :req-un [::groups]))

(def ^:private config (:value (get-config ::config [:ssh])))

(def ^:private servers-by-key
  (->>
    (:groups config)
    (mapcat
      (fn [{:keys [key user servers]}]
        (map
          (fn [{:keys [host name]}]
            {name {:key-file key :user user :host host}})
          servers)))
  (reduce merge)))

(defn list-servers
  "ssh servers # list servers configured for ssh access"
  {:yb/cat #{:infra}}
  [_]
  {:result/data servers-by-key
   :result/value (keys servers-by-key)})

(defn run-command
  "ssh <server> <command> # run a command on <server>"
  {:yb/cat #{:infra}}
  [{[_ server-name command] :match}]
  (if-let [config (get servers-by-key server-name)]
    (let [host (:host config)
          user (:user config)
          key-file (:key-file config)]
      (let [agent (ssh-agent {:use-system-ssh-agent false})]
        (add-identity agent {:private-key-path key-file})
        (let [session
              (session agent host {:strict-host-key-checking :no
                                   :username user})]
          (with-connection session
                           (let [result (ssh session {:cmd command})]
                             (or (:out result) (:error result)))))))
    {:result/error (str "No servers found for " server-name)}))

(cmd-hook #"ssh"
  #"^(\S+)\s(.+)" run-command
  #"^servers" list-servers)
