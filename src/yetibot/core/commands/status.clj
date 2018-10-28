(ns yetibot.core.commands.status
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.models.status :as model]
    [clj-time [core :refer [ago minutes hours days weeks years months]]]
    [inflections.core :refer [plural]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def empty-msg "No one has set their status")

(defn show-status
  "status # show statuses in the last 8 hours"
  {:yb/cat #{:util :info}}
  [{:keys [chat-source]}]
  (let [ss (model/status-since chat-source (-> 8 hours ago))]
    {:result/data ss
     :result/value (model/format-sts ss)}))

(defn show-status-since
  "status since <n> <minutes|hours|days|weeks|months> ago # show status since a given time"
  {:yb/cat #{:util :info}}
  [{[_ n unit] :match chat-source :chat-source}]
  (let [unit (plural unit) ; pluralize if singular
        unit-fn (ns-resolve 'clj-time.core (symbol unit))
        n (read-string n)]
    (if (number? n)
      (let [ss (model/status-since chat-source (-> n unit-fn ago))]
        {:result/value (model/format-sts ss)
         :result/data ss})
      {:result/error (str n " is not a number")})))

(defn set-status
  "status <message> # update your status"
  {:yb/cat #{:util :info}}
  [{:keys [match chat-source user] :as args}]
  (let [{:keys [adapter room]} chat-source]
    (model/add-status
      {:chat-source-adapter adapter
       :chat-source-room room
       :user-id (:id user)
       :status match})
    (show-status args)))

(cmd-hook #"status"
          #"since (.+) (minutes*|hours*|days*|weeks*|months*)( ago)*" show-status-since
          #".+" set-status
          _ show-status)
