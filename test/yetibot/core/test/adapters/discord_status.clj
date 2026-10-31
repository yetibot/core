(ns yetibot.core.test.adapters.discord-status
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [discljord.messaging :as messaging]
            [discljord.messaging.impl :as messaging-impl]
            [discljord.messaging.specs :as messaging-specs]
            [org.httpkit.client :as http]
            [discljord.util :as util]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.chat :as chat]
            [yetibot.core.handler :as handler]
            [midje.sweet :refer [fact facts =>]]))

(defn- status-call [name & args]
  (if-let [f (ns-resolve 'yetibot.core.adapters.discord name)]
    (apply f args)
    :missing-api))

(defn- receipt [value]
  (let [p (util/derefable-promise-chan)]
    (if (nil? value) (async/close! p) (async/>!! p value))
    p))

(defn- await! [ch]
  (let [[_ port] (async/alts!! [ch (async/timeout 3000)])]
    (when-not (= ch port)
      (throw (ex-info "Synthetic status command did not finish" {})))))

(def event
  {:id "status-command" :channel-id "status-channel"
   :content "!echo synthetic" :author {:id "synthetic-user"}})

(defn- with-status-session [evaluate check]
  (let [adapter (discord/make-discord {:name "synthetic-status"})
        conn (:conn adapter)
        _ (reset! conn {:rest :synthetic-rest
                        :status-rest :synthetic-status-rest})
        calls (atom [])
        clock (atom 0)
        jobs (atom [])
        ids (atom 0)
        schedule (fn [f]
                   (let [cancelled (atom false)]
                     (swap! jobs conj [f cancelled])
                     #(reset! cancelled true)))
        tick (fn [ms]
               (reset! clock (* 1000000 ms))
               (doseq [[f cancelled] @jobs]
                 (when-not @cancelled (f))))
        overrides
        (into {}
              (keep (fn [[sym value]]
                      (when-let [v (ns-resolve
                                     'yetibot.core.adapters.discord sym)]
                        [v value])))
              {'monotonic-time #(deref clock)
               'schedule-status! schedule})]
    (with-redefs-fn overrides
      #(with-redefs
         [chat/chat-source (fn [room] {:adapter :discord :room room})
          handler/handle-raw
          (fn [& _]
            (async/thread
              (evaluate)
              nil))
          messaging/create-message!
          (fn [_ target & opts]
            (let [id (str "synthetic-" (swap! ids inc))]
              (swap! calls conj [:create target id (apply hash-map opts)])
              (receipt {:id id :channel-id target})))
          messaging/edit-message!
          (fn [_ target id & opts]
            (swap! calls conj [:edit target id (apply hash-map opts)])
            (receipt {:id id :channel-id target}))
          messaging/delete-message!
          (fn [_ target id]
            (swap! calls conj [:delete target id])
            (receipt true))
          messaging/trigger-typing-indicator!
          (fn [_ target]
            (swap! calls conj [:typing target])
            (receipt true))]
         (check
           (fn [type message]
             (binding [chat/*adapter* adapter]
               (discord/handle-event type message conn (atom {:id "bot"}))))
           tick calls conn jobs)))))

(defn- start! [text]
  (status-call 'start-command-status! "status-thread" text))

(defn- update! [text]
  (status-call 'update-command-status! text))

(defn- finish! []
  (status-call 'finish-command-status!))

(defn- card [text]
  {:flags 32768
   :components [{:type 17 :components [{:type 10 :content text}]}]
   :allowed_mentions {:parse [] :replied_user false}})

(fact "status is request-bound and never falls back to direct IO"
  (with-status-session (constantly nil)
    (fn [_ _ calls _ _]
      (start! "Working") => :unavailable
      (update! "Working") => :unavailable
      (finish!) => :unavailable
      @calls => [])))

(fact "status coalesces snapshots and typing skips missed intervals"
  (let [ready (promise)
        release (promise)
        callbacks (promise)]
    (with-status-session
      (fn []
        (deliver callbacks [(bound-fn [s] (update! s))
                            (bound-fn [] (finish!))])
        (deliver ready (start! "Running query"))
        @release)
      (fn [event! tick calls conn jobs]
        (let [completion (event! :message-create event)]
          (try
            (deref ready 3000 :timeout) => :accepted
            (let [[update stop] @callbacks]
              (tick 0)
              @calls => [[:typing "status-thread"]
                         [:create "status-thread" "synthetic-1"
                          (card "Running query")]]
              (update "Finished query\nRunning docs") => :accepted
              (update "Finished query\nFinished docs") => :accepted
              (tick 999)
              (count @calls) => 2
              (tick 1000)
              (last @calls)
              => [:edit "status-thread" "synthetic-1"
                  (card "Finished query\nFinished docs")]
              (update "Finished query\nFinished docs") => :accepted
              (tick 2000)
              (count @calls) => 3
              (tick 8000)
              (tick 40000)
              (tick 40001)
              (count (filter #(= :typing (first %)) @calls)) => 3
              (stop) => :retired
              (update "Too late") => :inactive
              (tick 48000)
              (last @calls) => [:delete "status-thread" "synthetic-1"]
              (count (filter #(= :typing (first %)) @calls)) => 3)
            (finally (deliver release true)))
          (await! completion)
          (:command-statuses @conn) => {}
          (every? #(deref (second %)) @jobs) => true)))))

(fact "status invalid inputs are static and cannot mutate the collector"
  (let [done (promise)]
    (with-status-session
      (fn []
        (deliver done
          (mapv (fn [text]
                  (try (start! text)
                       :no-error
                       (catch IllegalArgumentException e
                         [(.getMessage e) (.getCause e)])))
                [nil 42 {} "" "  " (str/join (repeat 1801 "x"))])))
      (fn [event! _ calls _ _]
        (await! (event! :message-create event))
        @done => (vec (repeat 6 ["Invalid Discord command status" nil]))
        @calls => []))))

(fact "finish before start is permanent and all invalid targets fail before IO"
  (let [result (promise)]
    (with-status-session
      (fn []
        (deliver result
          [(mapv (fn [target]
                   (try
                     (status-call 'start-command-status! target "Working")
                     :no-error
                     (catch IllegalArgumentException e (.getMessage e))))
                 [nil 42 {} "" " "])
           (update! "Not started") (finish!) (start! "Not restarted")]))
      (fn [event! tick calls _ _]
        (await! (event! :message-create event))
        @result => [(vec (repeat 5 "Invalid Discord command status"))
                    :inactive :retired :inactive]
        (tick 8000)
        @calls => []))))

(fact "status never consumes buffered, early-answer or final-media indices"
  (let [ready (promise)
        release (promise)
        published (promise)
        final-release (promise)]
    (with-status-session
      (fn []
        (chat/send-msg "buffered prefix")
        (deliver ready (start! "Working"))
        @release
        (deliver published
          (discord/publish-command-output! "status-thread" ["early answer"]))
        @final-release
        (chat/send-msg
          "https://synthetic.invalid/generated-images/status-proof.png"))
      (fn [event! tick calls conn _]
        (with-redefs
          [yetibot.core.webapp.routes.images/image-store
           (atom {"status-proof" {:data "SGVsbG8="}})]
          (let [completion (event! :message-create event)]
            (try
              @ready => :accepted
              (tick 0)
              (count @calls) => 2
              (deliver release true)
              (deref published 3000 :timeout) => :published
              (mapv first @calls) => [:typing :create :create]
              (get-in @calls [2 3 :content]) => "early answer"
              (finally
                (deliver release true)
                (deliver final-release true)))
            (await! completion)
            (mapv #(subvec % 0 (min 3 (count %))) @calls)
            => [[:typing "status-thread"]
                [:create "status-thread" "synthetic-1"]
                [:create "status-thread" "synthetic-2"]
                [:create "status-channel" "synthetic-3"]
                [:edit "status-thread" "synthetic-2"]
                [:create "status-channel" "synthetic-4"]
                [:delete "status-thread" "synthetic-1"]]
            (get-in @calls [5 3 :stream :filename]) => "status-proof.png"
            (slurp (get-in @calls [5 3 :stream :content])) => "Hello"
            (mapv :id @(get-in @conn
                              [:commands ["status-channel" "status-command"]
                               :replies]))
            => ["synthetic-3" "synthetic-2" "synthetic-4"]
            (tick 8000)
            (count @calls) => 7))))))

(fact "late confirmed create is deleted once, never resent or made an answer"
  (let [ready (promise)
        release (promise)
        held (util/derefable-promise-chan)]
    (with-status-session
      (fn []
        (deliver ready (start! "Working"))
        @release
        (chat/send-msg "valid final"))
      (fn [event! tick calls conn jobs]
        (let [create messaging/create-message!]
          (with-redefs
            [messaging/create-message!
             (fn [& args]
               (let [result (apply create args)
                     opts (apply hash-map (drop 2 args))]
                 (if (:flags opts) held result)))]
            (let [completion (event! :message-create event)]
              (try
                @ready => :accepted
                (tick 0)
                (tick 1000)
                (finally (deliver release true)))
              (await! completion)
              (mapv first @calls) => [:typing :create :create]
              (:command-statuses @conn) => {}
              (every? #(deref (second %)) @jobs) => true
              (let [barrier (promise)
                    original @#'discord/status-receipt!]
                (with-redefs-fn
                  {#'discord/status-receipt!
                   (fn [& args]
                     (apply original args)
                     (when (= "late-status" (:id (last args)))
                       (deliver barrier true)))}
                  (fn []
                    (async/>!! held {:id "late-status"
                                    :channel-id "status-thread"})
                    (deref barrier 3000 :timeout) => true)))
              (tick 2000)
              (tick 10000)
              (last @calls) => [:delete "status-thread" "late-status"]
              (count (filter #(= :delete (first %)) @calls)) => 1)))))))

(fact "in-flight status edit cannot enqueue a dirty edit after retirement"
  (let [ready (promise)
        release (promise)
        callbacks (promise)
        held (util/derefable-promise-chan)]
    (with-status-session
      (fn []
        (deliver callbacks [(bound-fn [s] (update! s))
                            (bound-fn [] (finish!))])
        (deliver ready (start! "Working"))
        @release
        (chat/send-msg "valid final"))
      (fn [event! tick calls _ _]
        (let [edit messaging/edit-message!]
          (with-redefs
            [messaging/edit-message!
             (fn [& args]
               (let [result (apply edit args)]
                 (if (:flags (apply hash-map (drop 3 args))) held result)))]
            (let [completion (event! :message-create event)]
              (try
                @ready => :accepted
                (tick 0)
                (let [[update stop] @callbacks]
                  (update "Running query")
                  (tick 1000)
                  (update "Finished query")
                  (tick 2000)
                  (count (filter #(= :edit (first %)) @calls)) => 1
                  (stop) => :retired)
                (finally (deliver release true)))
              (await! completion)
              (async/>!! held {:id "synthetic-1"
                              :channel-id "status-thread"})
              (tick 40000)
              (mapv first @calls) => [:typing :create :edit :create :delete]
              (get-in @calls [3 3 :content]) => "valid final")))))))

(facts "ancillary failures and unknown creates never suppress valid finals"
  (doseq [failure [:create :edit :delete :typing]]
    (let [ready (promise)
          release (promise)
          update (promise)]
      (with-status-session
        (fn []
          (deliver update (bound-fn [] (update! "Finished query")))
          (deliver ready (start! "Working"))
          @release
          (chat/send-msg "valid final"))
        (fn [event! tick calls _ _]
          (let [create messaging/create-message!
                edit messaging/edit-message!
                delete messaging/delete-message!
                typing messaging/trigger-typing-indicator!]
            (with-redefs
              [messaging/create-message!
               (fn [& args]
                 (if (and (= failure :create)
                          (:flags (apply hash-map (drop 2 args))))
                   (receipt {:code 50013})
                   (apply create args)))
               messaging/edit-message!
               (fn [& args]
                 (if (= failure :edit)
                   (receipt {:code 50013})
                   (apply edit args)))
               messaging/delete-message!
               (fn [& args]
                 (if (= failure :delete)
                   (throw (ex-info "Never expose this cause" {}))
                   (apply delete args)))
               messaging/trigger-typing-indicator!
               (fn [& args]
                 (if (= failure :typing)
                   (throw (ex-info "Never expose this cause" {}))
                   (apply typing args)))]
              (let [completion (event! :message-create event)]
                (try
                  @ready => :accepted
                  (tick 0)
                  (@update)
                  (tick 1000)
                  (tick 2000)
                  (finally (deliver release true)))
                (await! completion)
                (count (filter #(= "valid final" (get-in % [3 :content]))
                               @calls)) => 1))))))))

(fact "new revisions register while a status receipt is unresolved"
  (let [ready (promise)
        release (promise)
        stale-callback (promise)
        invocation (atom 0)
        held (util/derefable-promise-chan)]
    (with-status-session
      (fn []
        (if (= 1 (swap! invocation inc))
          (do
            (deliver stale-callback (bound-fn [] (update! "obsolete")))
            (deliver ready (start! "Working"))
            @release)
          (chat/send-msg "new revision")))
      (fn [event! tick calls conn _]
        (let [create messaging/create-message!]
          (with-redefs
            [messaging/create-message!
             (fn [& args]
               (if (:flags (apply hash-map (drop 2 args)))
                 (do (apply create args) held)
                 (apply create args)))]
            (let [old (event! :message-create event)]
              (try
                @ready => :accepted
                (tick 0)
                (await! (event! :message-update
                                (assoc event :content "!echo newer")))
                (@stale-callback) => :stale
                (tick 8000)
                (count (filter #(= :typing (first %)) @calls)) => 1
                (:command-statuses @conn) => {}
                (finally (deliver release true)))
              (await! old)
              (:active-commands @conn) => {})))))))

(fact "retired callbacks stay stale after LRU eviction and across channels"
  (let [invocation (atom 0)
        callback (promise)]
    (with-redefs-fn {#'discord/command-cache-size 1}
      (fn []
        (with-status-session
          (fn []
            (when (= 1 (swap! invocation inc))
              (deliver callback
                (bound-fn [] [(start! "Late") (update! "Late") (finish!)])))
            (chat/send-msg "answer"))
          (fn [event! tick calls conn _]
            (await! (event! :message-create event))
            (await! (event! :message-create
                            (assoc event :channel-id "different-channel")))
            (count (:commands @conn)) => 1
            (let [before @calls]
              (@callback) => [:stale :stale :stale]
              (tick 40000)
              @calls => before)
            (mapv second @calls) => ["status-channel" "different-channel"]
            (:active-commands @conn) => {}))))))

(fact "public status reaches actual discljord create/edit/typing serialization"
  (let [create messaging/create-message!
        edit messaging/edit-message!
        delete messaging/delete-message!
        typing messaging/trigger-typing-indicator!
        post http/post
        patch http/patch
        http-delete http/delete
        wire (atom [])
        ready (promise)
        release (promise)
        update (promise)
        response (fn [method url options]
                   (let [payload (or (:body options)
                                     (:content (first (:multipart options))))]
                     (swap! wire conj
                            [method url
                             (when payload
                               (json/read-str payload :key-fn keyword))])
                     (delay
                       (if (or (= method :delete)
                               (str/ends-with? url "/typing"))
                         {:status 204 :body ""}
                         {:status 200
                          :body (json/write-str
                                  {:id "wire-card"
                                   :channel_id "status-thread"})}))))]
    (with-status-session
      (fn []
        (deliver update (bound-fn [] (update! "Finished @everyone <@123>")))
        (deliver ready (start! "Working @here <@&456>"))
        @release)
      (fn [event! tick _ conn _]
        (let [queue
              (async/chan
                16
                (map (fn [[endpoint & data :as event]]
                       (messaging-impl/dispatch-http
                         "synthetic-token" endpoint data)
                       event)))]
          (swap! conn assoc :rest queue :status-rest queue)
          (with-redefs
            [messaging/create-message! create
             messaging/edit-message! edit
             messaging/delete-message! delete
             messaging/trigger-typing-indicator! typing
             http/post
             (fn [url options]
               (if (str/includes? url "/channels/status-thread/")
                 (response :post url options)
                 (post url options)))
             http/patch
             (fn [url options]
               (if (str/includes? url "/channels/status-thread/")
                 (response :patch url options)
                 (patch url options)))
             http/delete
             (fn [url options]
               (if (str/includes? url "/channels/status-thread/")
                 (response :delete url options)
                 (http-delete url options)))]
            (let [completion (event! :message-create event)]
              (try
                @ready => :accepted
                (tick 0)
                (@update)
                (tick 1000)
                (finally (deliver release true)))
              (await! completion)
              (mapv first @wire) => [:post :post :patch :delete]
              (mapv #(select-keys (nth % 2)
                                 [:flags :components :allowed_mentions])
                    (subvec @wire 1 3))
              => [(card "Working @here <@&456>")
                  (card "Finished @everyone <@123>")]
              (every? #(not-any? (set (keys (nth % 2)))
                                [:content :embeds :stream :allowed-mentions])
                      (subvec @wire 1 3)) => true
              (every? #(str/includes? (second %) "/api/v9/") @wire) => true))
          (async/close! queue))))))

(fact "final answers and ancillary cleanup share one monotonic deadline"
  (let [ready (promise)
        release (promise)
        waits (atom [])]
    (with-status-session
      (fn []
        (deliver ready (start! "Working"))
        @release
        (chat/send-msg "valid final"))
      (fn [event! tick calls _ _]
        (let [create messaging/create-message!
              delete messaging/delete-message!]
          (with-redefs
            [messaging/create-message!
             (fn [& args]
               (let [p (apply create args)]
                 (if (:flags (apply hash-map (drop 2 args)))
                   p
                   (reify
                     clojure.lang.IBlockingDeref
                     (deref [_ ms _]
                       (swap! waits conj [:answer ms])
                       (tick 20000)
                       @p)))))
             messaging/delete-message!
             (fn [& args]
               (let [p (apply delete args)]
                 (reify
                   clojure.lang.IBlockingDeref
                   (deref [_ ms _]
                     (swap! waits conj [:cleanup ms])
                     @p))))]
            (let [completion (event! :message-create event)]
              (try
                @ready => :accepted
                (tick 0)
                (finally (deliver release true)))
              (await! completion)
              @waits => [[:answer 30000] [:cleanup 10000]]
              (mapv first @calls) => [:typing :create :create :delete]
              (get-in @calls [2 3 :content]) => "valid final")))))))

(fact "cleanup expiration never restarts a deadline or retries deletion"
  (let [ready (promise)
        release (promise)
        stop (promise)]
    (with-status-session
      (fn []
        (deliver stop (bound-fn [] (finish!)))
        (deliver ready (start! "Working"))
        @release)
      (fn [event! tick calls conn jobs]
        (let [completion (event! :message-create event)]
          (try
            @ready => :accepted
            (tick 0)
            (@stop) => :retired
            (let [lock (get-in @conn
                               [:commands ["status-channel" "status-command"]
                                :lock])
                  held (promise)
                  unlock (promise)
                  holder (future
                           (.lock lock)
                           (try (deliver held true) @unlock
                                (finally (.unlock lock))))]
              (try
                @held => true
                (tick 1000)
                (tick 30001)
                (every? #(deref (second %)) @jobs) => true
                (finally (deliver unlock true)))
              @holder)
            (tick 40000)
            (mapv first @calls) => [:typing :create]
            (finally (deliver release true)))
          ;; Terminal may make one later cleanup attempt under its own shared
          ;; final-flush deadline, not retry an already dispatched delete.
          (await! completion)
          (count (filter #(= :delete (first %)) @calls)) => 1)))))

(fact "typing keeps at most one unresolved request and never catches up"
  (let [ready (promise)
        release (promise)
        held (util/derefable-promise-chan)]
    (with-status-session
      (fn []
        (deliver ready (start! "Working"))
        @release)
      (fn [event! tick calls _ _]
        (let [typing messaging/trigger-typing-indicator!]
          (with-redefs
            [messaging/trigger-typing-indicator!
             (fn [& args] (apply typing args) held)]
            (let [completion (event! :message-create event)]
              (try
                @ready => :accepted
                (tick 0)
                (tick 8000)
                (tick 40000)
                (count (filter #(= :typing (first %)) @calls)) => 1
                (finally (deliver release true)))
              (await! completion)
              (async/>!! held true)
              (tick 48000)
              (count (filter #(= :typing (first %)) @calls)) => 1)))))))

(fact "late cleanup compares channel and ID and never deletes an answer ID"
  (doseq [answer-target ["status-thread" "other-thread"]]
    (let [ready (promise)
          release (promise)
          held (util/derefable-promise-chan)]
      (with-status-session
        (fn []
          (deliver ready (start! "Working"))
          @release
          (discord/publish-command-output! answer-target ["valid answer"]))
        (fn [event! tick calls _ _]
          (let [create messaging/create-message!
                edit messaging/edit-message!]
            (with-redefs
              [messaging/create-message!
               (fn [& args]
                 (let [p (apply create args)]
                   (if (:flags (apply hash-map (drop 2 args)))
                     held
                     (receipt {:id "shared-synthetic-id"
                               :channel-id (second args)}))))
               messaging/edit-message!
               (fn [& args]
                 (apply edit args)
                 (receipt {:id "shared-synthetic-id"
                           :channel-id (second args)}))]
              (let [completion (event! :message-create event)]
                (try
                  @ready => :accepted
                  (tick 0)
                  (finally (deliver release true)))
                (await! completion)
                (let [barrier (promise)
                      original @#'discord/status-receipt!]
                  (with-redefs-fn
                    {#'discord/status-receipt!
                     (fn [& args]
                       (apply original args)
                       (when (= "shared-synthetic-id" (:id (last args)))
                         (deliver barrier true)))}
                    (fn []
                      (async/>!! held {:id "shared-synthetic-id"
                                      :channel-id "status-thread"})
                      (deref barrier 3000 :timeout) => true)))
                (tick 1000)
                (filterv #(= :delete (first %)) @calls)
                => (if (= answer-target "status-thread")
                     []
                     [[:delete "status-thread" "shared-synthetic-id"]])))))))))

(fact "active status survives eviction and older retirement keeps newer status"
  (let [invocation (atom 0)
        old-ready (promise)
        new-ready (promise)
        old-release (promise)
        new-release (promise)
        old-callback (promise)]
    (with-redefs-fn {#'discord/command-cache-size 1}
      (fn []
        (with-status-session
          (fn []
            (case (swap! invocation inc)
              1 (do
                  (deliver old-callback (bound-fn [] (finish!)))
                  (deliver old-ready (start! "Old working"))
                  @old-release)
              2 (do (deliver new-ready (start! "New working")) @new-release)
              (chat/send-msg "other answer")))
          (fn [event! tick calls conn _]
            (let [old (event! :message-create event)
                  newer (atom nil)]
              (try
                @old-ready => :accepted
                (tick 0)
                (reset! newer
                        (event! :message-update
                                (assoc event :content "!echo newer")))
                @new-ready => :accepted
                (tick 1000)
                (await! (event! :message-create (assoc event :id "other")))
                (count (:commands @conn)) => 1
                (get-in @conn [:commands ["status-channel" "status-command"]])
                => nil
                (count (:command-statuses @conn)) => 1
                (deliver old-release true)
                (await! old)
                (@old-callback) => :stale
                (count (:command-statuses @conn)) => 1
                (tick 16000)
                (count (filter #(= :typing (first %)) @calls)) => 3
                (filterv #(= :delete (first %)) @calls)
                => [[:delete "status-thread" "synthetic-1"]]
                (finally
                  (deliver old-release true)
                  (deliver new-release true)))
              (await! old)
              (when @newer (await! @newer))
              (:command-statuses @conn) => {}
              (:active-commands @conn) => {}
              (filterv #(= :delete (first %)) @calls)
              => [[:delete "status-thread" "synthetic-1"]
                  [:delete "status-thread" "synthetic-2"]])))))))

(fact "the real daemon scheduler does not inherit caller dynamic bindings"
  (let [observed (promise)
        cancel
        (binding [chat/*target* "synthetic-binding-must-not-leak"]
          (#'discord/schedule-status!
            #(deliver observed [(.isDaemon (Thread/currentThread))
                                chat/*target*])))]
    (try
      (deref observed 3000 :timeout) => [true nil]
      (finally (cancel)))))
