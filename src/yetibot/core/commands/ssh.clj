(ns yetibot.core.commands.ssh
  (:require
    [schema.core :as sch]
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [clj-ssh.ssh :refer :all]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.config :refer [get-config]]))

(def schema {:groups [sch/Any]})

(def ^:private config (:value (get-config schema [:yetibot :ssh])))

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
  (keys servers-by-key))

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
              (session agent host {:strict-host-key-checking :no :username user})]
          (with-connection session
                           (let [result (ssh session {:cmd command})]
                             (or (:out result) (:error result)))))))
    (str "No servers found for " server-name)))

(cmd-hook #"ssh"
  #"^(\w+)\s(.+)" run-command
  #"^servers" list-servers)
