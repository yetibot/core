(ns yetibot.core.test.adapters.discord-thread-pipe
  (:require [clojure.string :as str]
            [discljord.messaging :as messaging]
            [midje.sweet :refer [fact =>]]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.chat :as chat]
            [yetibot.core.commands.reply-to]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.interpreter :as interp]
            [yetibot.core.test.adapters.discord-thread-timing :as timing]))

(def seen (atom []))
(defn before-cmd [_] (swap! seen conj :before) "hi")
(defn after-cmd [args]
  (swap! seen conj [:after (:raw args) (:data args) (:data-collection args)
                   (:room (:chat-source args)) chat/*target*
                   chat/*command-thread*]) "done")
(defn danger-cmd [_] (swap! seen conj :danger) "should not execute")
(defn map-cmd [_]
  {:result/value {:message "hi"} :result/data {:preserved true}
   :result/data-collection [1 2]})
(defn live-cmd [_]
  (discord/publish-command-output! chat/*target* ["live in thread"])
  (chat/suppress {}))
(defn status-cmd [_]
  (swap! seen conj [:status (discord/start-command-status! chat/*target* "Working")
                   chat/*target*]) "hi")
(cmd-hook #"pipe-before" _ before-cmd)
(cmd-hook #"pipe-after" _ after-cmd)
(cmd-hook #"pipe-danger" _ danger-cmd)
(cmd-hook #"pipe-map" _ map-cmd)
(cmd-hook #"pipe-live" _ live-cmd)
(cmd-hook #"pipe-status" _ status-cmd)

(defn- session [body check]
  (with-redefs [seen (atom [])]
    (#'timing/session #'timing/evaluate
      (fn [event! calls conn]
        (check (fn [kind event] (event! kind event)) calls conn
               (assoc timing/original :content body))))))
(defn- await! [r] (#'timing/await! r))
(defn- text [payload] (#'timing/text payload))
(defn- receipt [v] (deliver (promise) v))

(fact "a native thread pipe routes the literal result without executing it"
  (session "!echo pipe-danger | thread"
    (fn [event! calls conn event]
      (await! (event! :message-create event))
      (count (filter #(= :thread (first %)) @calls)) => 1
      (second (last @calls)) => "100"
      (str/starts-with? (text (last (last @calls))) "pipe-danger") => true
      @seen => []
      (get-in @conn [:commands ["200" "100"] :event]) => event)))

(fact "later pipe stages keep caller/source context and unchanged structured data"
  (session "!pipe-map | thread | pipe-after"
    (fn [event! calls _ event]
      (await! (event! :message-create event))
      @seen => [[:after {:message "hi"} {:preserved true} [1 2]
                 "200" "100" "100"]]
      (second (last @calls)) => "100"
      (str/starts-with? (text (last (last @calls))) "done") => true)))

(fact "thread directives reuse the same confirmed thread without sticky routing"
  (session "!echo hi | thread | thread"
    (fn [event! calls _ event]
      (await! (event! :message-create event))
      (second (last @calls)) => "100"
      (await! (event! :message-update (assoc event :content "!echo parent")))
      (mapv second (filter #(= :create (first %)) @calls)) => ["100" "200"]
      (await! (event! :message-update (assoc event :content "!echo back | thread")))
      (count (filter #(= :thread (first %)) @calls)) => 1
      (some #(and (= :create (first %)) (= "100" (second %))
                  (str/starts-with? (text (last %)) "back")) @calls) => true)))

(fact "failed native creation never retries or runs downstream commands"
  (let [posts (atom 0)]
    (session "!pipe-before | thread | pipe-after"
      (fn [event! calls _ event]
        (with-redefs [messaging/start-thread-with-message!
                      (fn [& _] (swap! posts inc) (receipt nil))]
          (await! (event! :message-create event))
          @seen => [:before]
          @posts => 1
          (await! (event! :message-update
                          (assoc event :content "!pipe-before | thread | pipe-after ")))
          @seen => [:before :before]
          @posts => 1
          (str/includes? (text (last (last @calls))) "not confirm") => true)))))

(fact "DM pipes fail at the thread stage without running later stages"
  (session "!pipe-before | thread | pipe-after"
    (fn [event! calls _ event]
      (await! (event! :message-create (dissoc event :guild-id)))
      @seen => [:before]
      (not-any? #(= :thread (first %)) @calls) => true
      (str/includes? (text (last (last @calls))) "not a DM") => true)))

(fact "a read-only lookup can confirm an existing attached thread without retrying"
  (session "!echo hi | thread"
    (fn [event! calls _ event]
      (with-redefs [messaging/start-thread-with-message!
                    (fn [& _] (swap! calls conj [:thread]) (receipt nil))
                    messaging/get-channel!
                    (fn [_ id]
                      (receipt (if (= id "200")
                                 {:id id :type 0 :guild-id "300"}
                                 {:id "100" :type 11 :guild-id "300"
                                  :parent-id "200"})))]
        (await! (event! :message-create event))
        (count (filter #(= :thread (first %)) @calls)) => 1
        (second (last @calls)) => "100"
        (str/starts-with? (text (last (last @calls))) "hi") => true))))

(fact "downstream live output stays threaded while explicit cross-channel sends stay explicit"
  (session "!echo hi | thread | pipe-live"
    (fn [event! calls _ event]
      (await! (event! :message-create event))
      (every? #(= "100" (second %))
              (filter #(#{:create :edit} (first %)) @calls)) => true
      (str/starts-with? (text (last (last @calls))) "live in thread") => true))
  (session "!echo hi | thread | replyto 999 explicit"
    (fn [event! calls _ event]
      (await! (event! :message-create event))
      (mapv second (filter #(= :create (first %)) @calls)) => ["999"])))

(fact "moving a pipeline retires parent progress and permits new thread progress"
  (session "!pipe-status | thread | pipe-status"
    (fn [event! _ conn event]
      (swap! conn assoc :status-rest :status)
      (with-redefs-fn {#'discord/schedule-status! (fn [_] (fn []))}
        (fn [] (await! (event! :message-create event))))
      @seen => [[:status :accepted "200"] [:status :accepted "100"]]
      (:command-statuses @conn) => {})))
