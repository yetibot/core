(ns yetibot.core.commands.cron
  "Scheduling capabilities for running commands in the future.
   See https://crontab.guru/examples.html for Cron syntax examples."
  (:require
    [yetibot.core.handler :refer [record-and-run-raw]]
    [yetibot.core.util.command :as command]
    [yetibot.core.interpreter :refer [*chat-source* *current-user*]]
    [yetibot.core.chat :refer [*target* *adapter-uuid* chat-data-structure]]
    [yetibot.core.db.cron :as db]
    [yetibot.core.util.format :refer [remove-surrounding-quotes]]
    [taoensso.timbre :refer [error debug info color-str]]
    [hara.io.scheduler.tab :as tab]
    [hara.io.scheduler :as cron]
    [yetibot.core.hooks :refer [cmd-hook]]))

;; use a single, initially-empty hara scheduler then add/remove tasks to it as
;; needed
;; Note: may want to consider a separate scheduler per Adapter instance. That
;; way you can pause/resume the scheduler only for your Adapter (vs all of
;; them).
(defonce scheduler (cron/scheduler {}))

(defn id-to-task-id [id] (str "task-" id))

(defn delete-task-if-exists! [id]
  ;; if the task doesn't exist this silently fails
  (cron/delete-task scheduler (id-to-task-id id)))

(defn- wire-cron!
  [{:keys [id user-id chat-source-room chat-source-adapter schedule cmd] :as cron}]
  (info "wire-cron!" (color-str :green (pr-str cron)))
  (let [chat-source {:room chat-source-room
                     :is-private false
                     :uuid (read-string chat-source-adapter)}
        handler (fn [t]
                  (info "cron triggered:" (pr-str t))
                  (info id chat-source-room chat-source-adapter schedule cmd)
                  (try
                    (binding [*chat-source* chat-source
                              *current-user* {:id user-id}
                              *target* chat-source-room
                              *adapter-uuid* (read-string chat-source-adapter)]
                      (let [expr (str command/config-prefix cmd)
                            [{:keys [error? timeout? result] :as expr-result}]
                            (record-and-run-raw expr nil nil)]
                        (info "cron result" (pr-str expr-result))
                        (if (and result (not error?) (not timeout?))
                          (chat-data-structure result)
                          (info
                            "Skipping cron because it errored or timed out"
                            (pr-str expr-result)))))
                    (catch Throwable e
                      (info "Error in cron:" e))))
        task-id (id-to-task-id id)]
    ;; if a task with the same ID already exists, delete it before re-adding it
    (delete-task-if-exists! id)
    ;; add the task to the scheduler!
    (info (color-str :yellow "scheduling task:")
          (pr-str (cron/add-task scheduler task-id
                                 {:handler handler
                                  :schedule schedule})))))

(defn- load-and-wire-crons! []
  (cron/start! scheduler)
  (run! wire-cron! (db/find-all)))

(defn- list-tasks []
  (cron/list-tasks scheduler))

;; load em up once on startup and start the scheduler
(defonce loader (future (load-and-wire-crons!)))

;; play with scheduler during dev
(comment
  (load-and-wire-crons!)
  (cron/stopped? scheduler)
  (cron/stop! scheduler)
  (cron/start! scheduler)
  (list-tasks)
  (cron/list-instances scheduler))

(defn format-cron-entity
  [{:keys [id chat-source-room schedule cmd]}]
  (str
    "[cmd=" cmd "] [schedule=" schedule "] [id=" id "] [channel="
    chat-source-room "]"))

(defn cron-cmd
  "cron <schedule> <command> # run <command> according to <schedule>.

   <schedule> must be a valid crontab string with 7 space-separated arguments:
   second minute hour day-of-week day-of-month month year

   Note: this is more expressive than standard cron which only supports 5
   arguments: minutes hours day-of-month month day-of-week

   See docs at http://docs.caudate.me/hara/hara-io-scheduler.html#schedule."
  [{[_ cron-schedule cmd] :match
    user :user
    {:keys [uuid room] :as chat-source} :chat-source}]

  ;; validate `cron-schedule` before persisting
  ;; Note: tab/valid-tab? makes very weak gaurantees
  (if (tab/valid-tab? cron-schedule)
    (let [cmd-no-quotes (remove-surrounding-quotes cmd)
          entity {:chat-source-adapter (pr-str uuid)
                  :chat-source-room room
                  :user-id (:id user)
                  :schedule cron-schedule
                  :cmd cmd-no-quotes}
          ;; need to merge the original back in because create returns an entity
          ;; with snake case and we want kebab case.
          [cron-entity] (db/create entity)
          kebab-cron-entity (merge cron-entity entity)]
      (wire-cron! kebab-cron-entity)
      (str "Cron item created. " (format-cron-entity kebab-cron-entity)))
    (str "Invalid cron format `" cron-schedule "`. "
         "See http://docs.caudate.me/hara/hara-io-scheduler.html#schedule"
         " for docs. ")))

(defn explain-cron-cmd
  "cron explain <schedule> # extract and label the components of <schedule>"
  [{[_ cron] :match}]
  "TODO")

(defn stop-cmd
  "cron stop # stop all scheduled tasks"
  [_]
  (cron/stop! scheduler)
  "Stopped cron tasks 🛑")

(defn start-cmd
  "cron start # start all scheduled tasks if not already started"
  [_]
  (if (cron/stopped? scheduler)
    (and
      (cron/start! scheduler)
      "Started cron tasks ✅")
    "Cron tasks are already running."))

(defn list-cmd
  "cron list # list the configured cron tasks"
  [{{:keys [uuid room]} :chat-source}]
  (let [crons (db/query {:where/map {:chat-source-adapter (pr-str uuid)}})]
    {:result/value (map format-cron-entity crons)
     :result/data crons}))

(defn remove-cmd
  "cron remove <task-id> # remove a task by id"
  [{[_ id] :match}]
  (let [int-id (read-string id)]
    (if-let [[entity] (db/query {:where/map {:id int-id}})]
      (do
        (db/delete int-id)
        (delete-task-if-exists! int-id)
        (str "Cron task " int-id " removed 🔥")
        )
      {:result/error
       (str "Couldn't find a task with id " int-id " 🧐")})))

(defn status-cmd
  "cron status # show the status of the scheduler"
  [_]
  (if (cron/stopped? scheduler)
    "Scheduler is stopped. Use `cron start` to resume."
    "Scheduler is running. Use `cron stop` to stop."))

(cmd-hook #"cron"
  #"status" status-cmd
  #"list" list-cmd
  #"stop" stop-cmd
  #"start" start-cmd
  #"remove\s(\d+)" remove-cmd
  ;; simplistic cron regex captures 7 space-separated cron args and a final arg
  ;; containing the command
  #"(\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+)\s+(.+)" cron-cmd
  ;; #"(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)$" explain-cron-cmd
  )
