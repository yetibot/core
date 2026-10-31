(ns yetibot.core.test.adapters.discord-status-review
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [discljord.connections :as ws]
            [discljord.events :as events]
            [discljord.messaging :as messaging]
            [discljord.messaging.impl :as impl]
            [discljord.messaging.specs :as ms]
            [discljord.specs :as ds]
            [discljord.util :as util]
            [org.httpkit.client :as http]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.test.adapters.discord-payload :as payload]
            [yetibot.core.chat :as chat]
            [yetibot.core.test.adapters.discord-status :as status]
            [midje.sweet :refer [fact =>]])
  (:import [java.util.concurrent TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(defn- session [evaluate check]
  (#'status/with-status-session evaluate check))

(defn- await-command [completion]
  (let [[_ port] (async/alts!! [completion (async/timeout 3000)])]
    (= port completion)))

(defn- response [id]
  {:status 200 :headers {:x-ratelimit-bucket "review-bucket"
                        :x-ratelimit-remaining "4"}
   :body (json/write-str {:id id :channel_id "review-sdk-channel"})})

(fact "R1 actual SDK endpoint agents isolate held status creates and edits"
  (doseq [operation [:create :edit]]
    (let [create messaging/create-message!
          edit messaging/edit-message!
          delete messaging/delete-message!
          typing messaging/trigger-typing-indicator!
          post http/post
          patch http/patch
          http-delete http/delete
          step impl/step-agent
          rate impl/update-rate-limit
          receipt @#'discord/status-receipt!
          created (promise)
          agents (atom #{})
          steps (atom [])
          rates (atom 0)
          held (promise)
          release-http (promise)
          final-http (promise)
          ready (promise)
          release-command (promise)
          update (promise)
          wire (atom [])
          status-edits (atom 0)
          answer-queue (messaging/start-connection! "review-sdk-answer")
          status-queue (messaging/start-connection! "review-sdk-status")
          request
          (fn [method url options]
            (let [payload (or (:body options)
                              (:content (first (:multipart options))))
                  body (when payload
                         (json/read-str payload :key-fn keyword))
                  card? (payload/status? body)
                  hold? (and card? (= method operation))]
              (swap! wire conj [method card?])
              (when (and (= method :edit) card?)
                (swap! status-edits inc))
              (when hold? (deliver held true))
              (when (and (not card?) (= method operation)
                         (= "review final" (:content (payload/logical body))))
                (deliver final-http true))
              (delay
                (when hold? (deref release-http 3000 :timeout))
                (if (or (= method :delete)
                        (str/ends-with? url "/typing"))
                  {:status 204 :body ""}
                  (response (if card? "review-card" "review-answer"))))))]
      (try
        (with-redefs
          [impl/step-agent
           (fn [process event]
             (let [result (step process event)]
               (when (#{"Bot review-sdk-answer" "Bot review-sdk-status"}
                       (::ds/token process))
                 (swap! steps conj (::ds/token process))
                 (swap! agents into (vals (::ms/endpoint-agents result))))
               result))
           impl/update-rate-limit
           (fn [limit headers]
             (when (= "review-bucket" (:x-ratelimit-bucket headers))
               (swap! rates inc))
             (rate limit headers))]
          (with-redefs-fn
            {#'discord/status-receipt!
             (fn [context create? text value]
               (receipt context create? text value)
               (when (= "review-card" (:id value))
                 (deliver created true)))}
            (fn []
              (session
                (fn []
                  (deliver update
                    (bound-fn []
                      (when (= operation :edit)
                        (discord/publish-command-output!
                          "review-sdk-channel" ["review final"]))
                      (discord/update-command-status! "Updated")))
                  (deliver ready
                    (discord/start-command-status!
                    "review-sdk-channel" "Working"))
                  @release-command
                  (when (= operation :create) (chat/send-msg "review final")))
                (fn [event! tick _ conn _]
                  (swap! conn assoc
                         :rest answer-queue :status-rest status-queue)
                  (with-redefs
                    [messaging/create-message! create
                     messaging/edit-message! edit
                     messaging/delete-message! delete
                     messaging/trigger-typing-indicator! typing
                     http/post
                     (fn [url opts]
                       (if (str/includes? url "/channels/review-sdk-channel/")
                         (request :create url opts)
                         (post url opts)))
                     http/patch
                     (fn [url opts]
                       (if (str/includes? url "/channels/review-sdk-channel/")
                         (request :edit url opts)
                         (patch url opts)))
                     http/delete
                     (fn [url opts]
                       (if (str/includes? url "/channels/review-sdk-channel/")
                         (request :delete url opts)
                         (http-delete url opts)))]
                    ;; Use the same channel for the status and terminal answer.
                    (let [completion
                          (event! :message-create
                                  (assoc status/event
                                         :channel-id "review-sdk-channel"))]
                      (try
                        (deref ready 3000 :timeout) => :accepted
                        (tick 0)
                        (when (= operation :edit)
                          ;; A receipt barrier, not a cadence sleep.
                          (deref created 3000 :timeout) => true
                          (@update)
                          (tick 1000))
                        (deref held 3000 :timeout) => true
                        (deliver release-command true)
                        (deref final-http 500 :timeout) => true
                        (finally
                          (deliver release-http true)
                          (deliver release-command true)))
                      (await-command completion) => true
                      (mapv :id
                            @(get-in @conn
                                     [:commands ["review-sdk-channel"
                                                 "status-command"] :replies]))
                      => ["review-answer"]
                      (count (filter #(= [operation true] %) @wire))
                      => 1)))))))
        (set @steps) => #{"Bot review-sdk-answer" "Bot review-sdk-status"}
        (pos? @rates) => true
        (finally
          (deliver release-http true)
          (deliver release-command true)
          (doseq [a @agents] (await-for 3000 a))
          (messaging/stop-connection! status-queue)
          (messaging/stop-connection! answer-queue))))))

(fact "R3 terminal lock uses remaining deadline and retires without acquisition"
  (let [ready (promise)
        release (promise)
        clock (atom 0)
        timed (atom [])
        lock (proxy [ReentrantLock] []
               (tryLock
                 ([] (proxy-super tryLock))
                 ([n unit]
                  (swap! timed conj [n unit])
                  (proxy-super tryLock 0 TimeUnit/NANOSECONDS))))]
    (session
      (fn []
        (deliver ready (discord/start-command-status! "status-thread" "Work"))
        @release
        (chat/send-msg "final"))
      (fn [event! _ _ conn _]
        (swap! conn assoc-in
               [:active-commands ["status-channel" "status-command"]]
               {:revision (atom nil) :lock lock :replies (atom [])
                :running (atom #{})})
        (with-redefs-fn
          {#'discord/monotonic-time #(deref clock)
           #'discord/live-deadline
           (fn []
             (let [deadline (+ @clock 30000000000)]
               (swap! clock + 20000000000)
               deadline))}
          (fn []
            (.lock lock)
            (let [completion (event! :message-create status/event)]
              (try
                (deref ready 3000 :timeout) => :accepted
                (deliver release true)
                (let [[_ port]
                      (async/alts!! [completion (async/timeout 500)])]
                  (= completion port) => true)
                (:active-commands @conn) => {}
                @timed => [[10000000000 TimeUnit/NANOSECONDS]]
                (.isHeldByCurrentThread lock) => true
                (finally
                  (.unlock lock)
                  (deliver release true)))
              (await-command completion) => true)))))))

(fact "R2 terminal retirement reserves cleanup before a pre-lock tick"
  (let [ready (promise)
        release (promise)
        retire @#'discord/retire-status!
        interleaved (atom false)]
    (session
      (fn []
        (deliver ready (discord/start-command-status! "status-thread" "Work"))
        @release
        (chat/send-msg "final"))
      (fn [event! tick calls _ _]
        (with-redefs-fn
          {#'discord/retire-status!
           (fn [context]
             (retire context)
             (when (and (:live? context)
                        (compare-and-set! interleaved false true))
               (#'discord/status-tick! context)))}
          (fn []
            (let [completion (event! :message-create status/event)]
              (try
                (deref ready 3000 :timeout) => :accepted
                (tick 0)
                (finally (deliver release true)))
              (await-command completion) => true
              @interleaved => true
              (mapv first @calls) => [:typing :create :create :delete])))))))

(fact "R4 late ID during answer timeout retains its original cleanup job"
  (let [ready (promise)
        release (promise)
        held (util/derefable-promise-chan)
        confirmed (promise)
        state (promise)
        receipt @#'discord/status-receipt!]
    (try
      (session
        (fn []
          (deliver ready
            (discord/start-command-status! "status-thread" "Work"))
          @release
          (chat/send-msg "final"))
        (fn [event! tick calls _ jobs]
          (let [create messaging/create-message!]
            (with-redefs-fn
              {#'discord/status-receipt!
               (fn [context create? text value]
                 (receipt context create? text value)
                 (when (= "review-late-card" (:id value))
                   (deliver state (:status context))
                   (deliver confirmed true)))}
              (fn []
                (with-redefs
                  [messaging/create-message!
                   (fn [& args]
                     (if (payload/status? (apply hash-map (drop 2 args)))
                       (do (apply create args) held)
                       (reify clojure.lang.IBlockingDeref
                         (deref [_ ms timeout]
                           (tick 25000)
                           (async/>!! held {:id "review-late-card"
                                           :channel-id "status-thread"})
                           (deref confirmed 3000 :timeout)
                           (tick 30000)
                           timeout))))]
                  (let [completion (event! :message-create status/event)]
                    (try
                      (deref ready 3000 :timeout) => :accepted
                      (tick 0)
                      (finally (deliver release true)))
                    (await-command completion) => true
                    (deref confirmed 3000 :timeout) => true
                    (:cleanup-deadline @@state) => 55000000000
                    (some #(not @(second %)) @jobs) => true
                    (tick 31000)
                    (tick 54000)
                    (tick 55001)
                    (filterv #(= :delete (first %)) @calls)
                    => [[:delete "status-thread" "review-late-card"]]
                    (:cleanup-deadline @@state) => 55000000000
                    (every? #(deref (second %)) @jobs) => true)))))))
      (finally
        (deliver release true)
        (async/close! held)))))

(fact "R1 adapter starts two SDK queues with one gateway and stops its lane"
  (let [token "review-lifecycle-token"
        adapter (discord/make-discord {:name "review-lifecycle" :token token})
        start messaging/start-connection!
        stop messaging/stop-connection!
        connect ws/connect-bot!
        pump events/message-pump!
        user messaging/get-current-user!
        queues (atom [])
        stopped (atom [])
        gateways (atom 0)
        cancelled (atom 0)
        gateway :review-lifecycle-gateway
        card (atom {:active? true :text "Work"
                    :cancel #(swap! cancelled inc)})]
    (with-redefs
      [messaging/start-connection!
       (fn [value]
         (let [queue (start value)]
           (when (= value token) (swap! queues conj queue))
           queue))
       messaging/stop-connection!
       (fn [queue]
         (if (= queue gateway)
           (swap! stopped conj queue)
           (do
             (when (some #(identical? queue %) @queues)
               (swap! stopped conj queue))
             (stop queue))))
       ws/connect-bot!
       (fn [value & args]
         (if (= value token)
           (do (swap! gateways inc) gateway)
           (apply connect value args)))
       events/message-pump!
       (fn [channel callback]
         (when-not (identical? channel (:event @(:conn adapter)))
           (pump channel callback)))
       messaging/get-current-user!
       (fn [queue]
         (if (some #(identical? queue %) @queues)
           (#'status/receipt {:id "review-lifecycle-bot"})
           (user queue)))]
      (try
        (discord/start adapter)
        (count @queues) => 2
        @gateways => 1
        (let [{:keys [rest status-rest]} @(:conn adapter)]
          (identical? rest status-rest) => false
          (some #(identical? rest %) @queues) => true
          (some #(identical? status-rest %) @queues) => true
          (swap! (:conn adapter) assoc :command-statuses {[:review] card})
          (discord/stop adapter)
          @stopped => [status-rest gateway]
          (:status-rest @(:conn adapter)) => nil
          (:command-statuses @(:conn adapter)) => {}
          (:active? @card) => false
          @cancelled => 1)
        (finally
          (doseq [queue @queues] (stop queue))
          (when-let [channel (:event @(:conn adapter))]
            (async/close! channel)))))))

(fact "R1 unavailable status lane never falls back to the answer connection"
  (let [result (promise)]
    (session
      (fn []
        (deliver result
          (discord/start-command-status! "status-thread" "Work"))
        (chat/send-msg "normal answer"))
      (fn [event! tick calls conn _]
        (swap! conn dissoc :status-rest)
        (await-command (event! :message-create status/event)) => true
        @result => :unavailable
        (tick 40000)
        (mapv first @calls) => [:create]
        (get-in @calls [0 3 :content]) => "normal answer"))))

(fact "R1 ancillary initialization failure leaves normal REST usable"
  (let [adapter (discord/make-discord {:name "review-init-failure"
                                     :token "review-init-failure-token"})
        start messaging/start-connection!
        connect ws/connect-bot!
        user messaging/get-current-user!
        pump events/message-pump!
        n (atom 0)
        answer (atom nil)]
    (with-redefs
      [messaging/start-connection!
       (fn [token]
         (if (= "review-init-failure-token" token)
           (if (= 1 (swap! n inc))
             (reset! answer (start token))
             (throw (ex-info "Synthetic ancillary failure" {})))
           (start token)))
       ws/connect-bot!
       (fn [token & args]
         (if (= "review-init-failure-token" token)
           :review-init-gateway
           (apply connect token args)))
       messaging/get-current-user!
       (fn [queue]
         (if (identical? queue @answer)
           (#'status/receipt {:id "review-init-bot"})
           (user queue)))
       events/message-pump!
       (fn [channel callback]
         (when-not (identical? channel (:event @(:conn adapter)))
           (pump channel callback)))]
      (try
        (discord/start adapter)
        @n => 2
        (identical? @answer (:rest @(:conn adapter))) => true
        (:status-rest @(:conn adapter)) => nil
        @(:connected? adapter) => true
        (finally
          (when @answer (messaging/stop-connection! @answer))
          (when-let [channel (:event @(:conn adapter))]
            (async/close! channel)))))))

(fact "R1 every ancillary operation uses only the private REST lane"
  (let [ready (promise)
        release (promise)
        update (promise)
        lanes (atom [])]
    (session
      (fn []
        (deliver update
          (bound-fn [] (discord/update-command-status! "Updated")))
        (deliver ready
          (discord/start-command-status! "status-thread" "Work"))
        @release
        (chat/send-msg "final"))
      (fn [event! tick _ _ _]
        (let [create messaging/create-message!
              edit messaging/edit-message!
              delete messaging/delete-message!
              typing messaging/trigger-typing-indicator!
              record (fn [kind f args]
                       (when (= "status-thread" (second args))
                         (swap! lanes conj [kind (first args)]))
                       (apply f args))]
          (with-redefs
            [messaging/create-message!
             (fn [& args] (record :create create args))
             messaging/edit-message!
             (fn [& args] (record :edit edit args))
             messaging/delete-message!
             (fn [& args] (record :delete delete args))
             messaging/trigger-typing-indicator!
             (fn [& args] (record :typing typing args))]
            (let [completion (event! :message-create status/event)]
              (try
                (deref ready 3000 :timeout) => :accepted
                (tick 0)
                (@update)
                (tick 1000)
                (finally (deliver release true)))
              (await-command completion) => true
              @lanes => [[:typing :synthetic-status-rest]
                         [:create :synthetic-status-rest]
                         [:edit :synthetic-status-rest]
                         [:delete :synthetic-status-rest]])))))))

(fact "failed status edits are attempted once across later snapshots and ticks"
  (let [ready (promise)
        release (promise)
        update (promise)
        edits (atom 0)]
    (session
      (fn []
        (deliver update
          (bound-fn [text] (discord/update-command-status! text)))
        (deliver ready
          (discord/start-command-status! "status-thread" "Work"))
        @release
        (chat/send-msg "final"))
      (fn [event! tick calls _ _]
        (let [edit messaging/edit-message!]
          (with-redefs
            [messaging/edit-message!
             (fn [lane target id & options]
               (if (and (= target "status-thread")
                        (payload/status? (apply hash-map options)))
                 (do (swap! edits inc) (#'status/receipt {:code 50013}))
                 (apply edit lane target id options)))]
            (let [completion (event! :message-create status/event)]
              (try
                (deref ready 3000 :timeout) => :accepted
                (tick 0)
                (@update "First edit") => :accepted
                (tick 1000)
                (@update "Later snapshot") => :accepted
                (tick 2000)
                (tick 10000)
                (tick 20000)
                @edits => 1
                (finally (deliver release true)))
              (await-command completion) => true
              @edits => 1
              (count (filter #(= "final" (get-in % [3 :content])) @calls))
              => 1)))))))

(fact "R3 expired and interrupted acquisitions expose only static errors"
  (doseq [mode [:expired :interrupted]]
    (let [attempts (atom 0)
          lock (proxy [ReentrantLock] []
                 (tryLock
                   ([] (proxy-super tryLock))
                   ([n unit]
                    (swap! attempts inc)
                    (throw (InterruptedException. "synthetic secret")))))]
      (with-redefs-fn {#'discord/monotonic-time (constantly 100)}
        (fn []
          (with-bindings {#'discord/*delivery-deadline*
                          (if (= mode :expired) 100 200)}
            (try
              (#'discord/acquire-command-lock! lock)
              :unexpected
              (catch Exception e
                [(.getMessage e) (.getCause e) (ex-data e)]))
            => ["Discord live delivery failed" nil {}])))
      @attempts => (if (= mode :expired) 0 1)
      (.isLocked lock) => false)))
