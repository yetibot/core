(ns yetibot.core.test.adapters.discord-thread-timing
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [discljord.messaging :as messaging]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.adapters.discord-components :as components]
            [yetibot.core.chat :as chat]
            [yetibot.core.commands.echo]
            [yetibot.core.commands.error]
            [yetibot.core.commands.thread]
            [yetibot.core.interpreter :as interp]
            [yetibot.core.parser :as parser]
            [yetibot.core.handler :as handler]
            [yetibot.core.models.admin :as admin]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.db.observe :as observers]
            [yetibot.core.models.users :as users]
            [yetibot.core.util.command :as command]))

(def original
  {:id "100" :channel-id "200" :guild-id "300"
   :author {:id "alice" :username "alice"}
   :content "!thread echo before | echo after %s"})

(defn- receipt [value] (deliver (promise) value))
(defn- await! [result]
  (when result
    (let [[_ port] (async/alts!! [result (async/timeout 5000)])]
      (when-not (= port result) (throw (Exception. "Command timeout"))))))

(defn- text [payload]
  (->> (get-in payload [:components 0 :components])
       (filter #(= 10 (:type %))) (map :content) (str/join "\n")))

(defn- session [evaluate check]
  (let [adapter (discord/make-discord {:name "thread-test"})
        conn (:conn adapter) calls (atom []) ids (atom 0)]
    (reset! conn {:rest :rest})
    (reset! (:yetibot-user adapter) {:id "bot"})
    (with-redefs [users/users (atom {})
                  channel/settings-for-chat-source (constantly {})
                  observers/find-all (constantly [])
                  messaging/get-channel!
                  (fn [_ id] (receipt {:id id :type 0 :guild-id "300"}))
                  messaging/start-thread-with-message!
                  (fn [_ parent message title duration]
                    (swap! calls conj [:thread parent message title duration])
                    (receipt {:id message :type 11 :parent-id parent}))
                  handler/record-and-run-raw
                  (fn [body user bot] (evaluate body user bot))
                  messaging/create-message!
                  (fn [_ target & options]
                    (let [id (str (swap! ids inc))]
                      (swap! calls conj
                             [:create target id (apply hash-map options)])
                      (receipt {:id id :channel-id target})))
                  messaging/edit-message!
                  (fn [_ target id & options]
                    (swap! calls conj
                           [:edit target id (apply hash-map options)])
                    (receipt {:id id :channel-id target}))
                  messaging/delete-message!
                  (fn [_ target id]
                    (swap! calls conj [:delete target id]) (receipt true))]
      (binding [chat/*adapter* adapter]
        (check (fn [kind event]
                 (discord/handle-event kind event conn (:yetibot-user adapter)))
               calls conn)))))

(defn- evaluate [body user bot]
  (let [result (handler/handle-parsed-expr interp/*chat-source* user bot
                                          (parser/parser (subs body 1)))]
    [{:result (or (:value result) (:error result))}]))

(fact "thread runs the complete pipeline in one confirmed message thread"
  (let [seen (atom [])]
    (session (fn [body user bot]
               (swap! seen conj [body (:id user)])
               (evaluate body user bot))
      (fn [event! calls conn]
        (await! (event! :message-create original))
        (mapv first @calls) => [:thread :create]
        (second (last @calls)) => "100"
        (first @seen) => ["!echo before | echo after %s" "alice"]
        (str/starts-with? (text (last (last @calls))) "after before") => true
        (get-in @conn [:commands ["200" "100"] :event]) => original))))

(fact "edits reuse the same thread and replace its reply"
  (session evaluate
    (fn [event! calls _]
      (await! (event! :message-create original))
      (await! (event! :message-update
                      {:id "100" :channel-id "200"
                       :content "!thread echo changed"}))
      (mapv first @calls) => [:thread :create :edit]
      (second (last @calls)) => "100"
      (str/starts-with? (text (last (last @calls))) "changed") => true)))

(fact "unsupported contexts never execute the nested command"
  (let [runs (atom 0)]
    (session (fn [& _] (swap! runs inc))
      (fn [event! calls _]
        (await! (event! :message-create (dissoc original :guild-id)))
        @runs => 0
        (mapv first @calls) => [:create]
        (str/includes? (text (last (last @calls))) "server") => true))))

(fact "unknown thread creation is not retried on edits or executed"
  (let [runs (atom 0) attempts (atom 0)]
    (session (fn [& _] (swap! runs inc))
      (fn [event! calls _]
        (with-redefs [messaging/start-thread-with-message!
                      (fn [& _] (swap! attempts inc) (receipt nil))]
          (await! (event! :message-create original))
          (await! (event! :message-update
                          (assoc original :content "!thread echo edit")))
          @attempts => 1
          @runs => 0
          (str/includes? (text (last (last @calls))) "not run") => true)))))

(fact "the wrapper obeys its own command and admin restrictions"
  (doseq [[enabled? admin-only?] [[false false] [true true]]]
    (let [runs (atom 0)]
      (session (fn [& _] (swap! runs inc))
        (fn [event! calls _]
          (with-redefs [command/command-enabled? (constantly enabled?)
                        admin/admin-only-command? (constantly admin-only?)
                        admin/user-is-admin? (constantly false)]
            (await! (event! :message-create original))
            @runs => 0
            (not-any? #(= :thread (first %)) @calls) => true))))))

(fact "buffered ordinary rich answers show elapsed command time"
  (session (fn [& _] (Thread/sleep 110) [{:result "done"}])
    (fn [event! calls _]
      (await! (event! :message-create
                      (assoc original :content "!echo timed")))
      (boolean (re-find #"(?m)^-# 0\.[1-9]s$"
                        (text (last (last @calls))))) => true)))

(fact "long rich replies reserve footer space and attach the exact answer"
  (let [answer (apply str (repeat 4000 "x"))]
    (session (fn [& _] [{:result answer}])
      (fn [event! calls _]
        (await! (event! :message-create
                        (assoc original :content "!echo large")))
        (let [payload (last (last @calls))]
          (<= (count (text payload)) components/max-text-length) => true
          (get-in payload [:stream :filename]) => "answer.md"
          (when-let [stream (get-in payload [:stream :content])]
            (slurp stream)) => answer
          (boolean (re-find #"(?m)^-# [0-9]+\.[0-9]s$" (text payload)))
          => true)))))

(fact "thread preserves trusted source, input attachments and real caller"
  (let [seen (atom nil)]
    (session (fn [body user _]
               (reset! seen {:body body :source interp/*chat-source*
                             :target chat/*target* :thread chat/*command-thread*
                             :user user})
               [{:result "ok"}])
      (fn [event! _ _]
        (let [event (assoc original :attachments [{:url "https://example.org/a"}])]
          (await! (event! :message-create event))
          (get-in @seen [:source :raw-event]) => event
          (get-in @seen [:source :room]) => "200"
          (get-in @seen [:user :id]) => "alice"
          (:target @seen) => "100"
          (:thread @seen) => "100")))))

(fact "the entire nested expression is interpreted normally in the thread"
  (session evaluate
    (fn [event! calls _]
      (await! (event! :message-create
                      (assoc original :content
                             "!thread echo `echo nested` | echo got %s")))
      (str/starts-with? (text (last (last @calls))) "got nested") => true
      (second (last @calls)) => "100")))

(fact "nested-thread contexts fail before thread creation or command execution"
  (let [runs (atom 0)]
    (session (fn [& _] (swap! runs inc))
      (fn [event! calls _]
        (with-redefs [messaging/get-channel!
                      (fn [& _] (receipt {:type 11 :guild-id "300"}))]
          (await! (event! :message-create original))
          @runs => 0
          (not-any? #(= :thread (first %)) @calls) => true
          (str/includes? (text (last (last @calls))) "not run") => true)))))

(fact "transport exceptions cannot rerun creation or execute the nested command"
  (let [runs (atom 0) posts (atom 0)]
    (session (fn [& _] (swap! runs inc))
      (fn [event! calls _]
        (with-redefs [messaging/start-thread-with-message!
                      (fn [& _] (swap! posts inc)
                        (throw (Exception. "private upstream response")))]
          (await! (event! :message-create original))
          (await! (event! :message-update
                          (assoc original :content "!thread echo retry")))
          @posts => 1
          @runs => 0
          (str/includes? (text (last (last @calls))) "private") => false)))))

(fact "command errors also have a measured rich footer"
  (session evaluate
    (fn [event! calls _]
      (await! (event! :message-create
                      (assoc original :content "!thread error nope")))
      (let [body (text (last (last @calls)))]
        (str/includes? body "nope") => true
        (boolean (re-find #"(?m)^-# [0-9]+[.][0-9]s$" body)) => true
        (second (last @calls)) => "100"))))

(fact "a live rich answer is timed immediately and at terminal reconciliation"
  (session (fn [& _]
             (discord/publish-command-output! chat/*target* ["live answer"])
             (Thread/sleep 110)
             [])
    (fn [event! calls _]
      (await! (event! :message-create
                      (assoc original :content "!echo live")))
      (mapv first @calls) => [:create :edit]
      (boolean (re-find #"(?m)^-# [0-9]+[.][0-9]s$"
                        (text (last (first @calls))))) => true
      (boolean (re-find #"(?m)^-# 0[.][1-9]s$"
                        (text (last (last @calls))))) => true)))
