(ns yetibot.core.test.commands.cron
  (:require [midje.sweet :refer [fact => throws]]
            [hara.io.scheduler :as scheduler]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.commands.cron :as cron]
            [yetibot.core.db.cron :as db]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.models.channel :as channel]))

(fact "scheduled commands cannot manage cron, directly or through aliases"
  (try (deref cron/loader) (catch Exception _ nil))
  (try (deref alias/loader) (catch Exception _ nil))
  (with-redefs [db/query (fn [& _] (throw (AssertionError. "Unexpected read")))
                db/create (fn [& _] (throw (AssertionError. "Unexpected write")))
                db/delete (fn [& _] (throw (AssertionError. "Unexpected delete")))
                hooks/hooks (atom @hooks/hooks)
                hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                channel/settings-for-chat-source (constantly {})
                handler/add-user-message-to-history (fn [& _])]
    (binding [cron/*scheduled?* true]
      (doseq [f [cron/cron-cmd cron/remove-cmd cron/list-cmd
                 cron/start-cmd cron/stop-cmd]]
        (fact (f {}) => (throws clojure.lang.ExceptionInfo #"cannot manage")))
      (#'alias/wire-alias {:cmd-name "nestedcron" :cmd "cron list"})
      (let [result ((#'alias/build-alias-cmd-fn "nestedcron")
                     {:args "" :user {:id "u"} :yetibot-user {:id "bot"}})]
        (boolean (:result/error result)) => true)))
  (scheduler/stop! cron/scheduler))

(fact "the actual scheduled handler carries the no-management boundary"
  (let [handler (atom nil) scheduled? (atom nil)]
    (with-redefs [scheduler/delete-task (fn [& _])
                  scheduler/add-task (fn [_ _ task] (reset! handler (:handler task)))
                  handler/record-and-run-raw
                  (fn [& _] (reset! scheduled? cron/*scheduled?*) [])]
      (cron/wire-cron! {:id 1 :user-id "u" :chat-source-room "test"
                       :chat-source-adapter (pr-str :test)
                       :schedule "0 0 8 * * * *" :cmd "weather"})
      (@handler nil)
      @scheduled? => true)))
