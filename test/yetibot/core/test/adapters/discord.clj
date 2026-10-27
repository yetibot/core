(ns yetibot.core.test.adapters.discord
  (:require             [clojure.core.async :as async]
                        [yetibot.core.adapters.discord :as discord]
                        [discljord.messaging :as messaging]
                        [yetibot.core.handler :as handler]
                        [yetibot.core.models.users :as users]
                        [yetibot.core.models.channel :as channel]
                        [yetibot.core.interpreter :as interp]
                        [yetibot.core.chat :as chat]
                        [yetibot.core.commands.observe :as obs]
                        [yetibot.core.commands.echo]
                        [yetibot.core.models.history :as history]
                        [yetibot.core.db.observe :as observers]
                        [yetibot.core.webapp.routes.images :as images]
                        [midje.sweet :refer [fact facts anything => provided]]))

(facts
 "about handle-event message-reaction-add"
 (fact
  "deletes yetibot message when reacted with x"
  (discord/handle-event :message-reaction-add
                        {:message-id 123
                         :channel-id 456
                         :message-author-id 111
                         :emoji {:name "❌"}}
                        (atom nil)
                        (atom {:id 111})) => "I did it"
  (provided (messaging/delete-message! anything anything anything) => "I did it"))
 (fact
  "when reacting to a non delete yetibot message do nothing"
  (discord/handle-event :message-reaction-add
                        {:message-id 123
                         :channel-id 456
                         :message-author-id 111
                         :emoji {:name "🍿"}}
                        (atom nil)
                        (atom {:id 111})) => nil))


(facts "reaction events distinguish the reactor from the message author"
  (doseq [member? [true false]
          author-id? [true false]]
    (let [author {:id "author" :username "alice"}
          reactor {:id "reactor" :username "bob"}
          event (cond-> {:message-id "message" :channel-id "channel"
                         :user-id "reactor" :emoji {:name "💬"}}
                  member? (assoc :guild-id "guild" :member {:user reactor})
                  author-id? (assoc :message-author-id "author"))
          lookups (atom [])]
      (with-redefs [users/users (atom {})
                    chat/chat-source (fn [room] {:room room})
                    messaging/get-channel-message!
                    (fn [& _] (delay {:content "hello" :author author}))
                    messaging/get-user!
                    (fn [_ id] (swap! lookups conj id) (delay reactor))
                    handler/handle-raw
                    (fn [source user type _ info]
                      {:source source :user user :type type :info info
                       :target chat/*target*})]
        (let [result (discord/handle-event :message-reaction-add event
                       (atom {:rest :rest}) (atom {:id "bot"}))]
          (fact (select-keys (:user result) [:id :username :yetibot?])
                => {:id "reactor" :username "bob" :yetibot? false})
          (fact (select-keys (get-in result [:info :message-user])
                            [:id :username :yetibot?])
                => {:id "author" :username "alice" :yetibot? false})
          (fact (select-keys (:info result) [:body :reaction])
                => {:body "hello" :reaction "💬"})
          (fact (:type result) => :react)
          (fact (:target result) => "channel")
          (fact (get-in result [:source :raw-event]) => event)
          (fact @lookups => (if member? [] ["reactor"])))))))

(fact "Discord reactions render both observer usernames from an empty cache"
  (let [rendered (promise)
        observer {:event-type "react" :pattern "💬"
                  :cmd (str "echo {{body}} by {{message-username}} via "
                            "{{reaction}} reaction from {{username}} "
                            "in {{channel}}")}
        event {:message-id "M" :channel-id "C" :user-id "reactor"
               :emoji {:name "💬"}
               :member {:user {:id "reactor" :username "bob"}}}]
    (with-redefs [users/users (atom {})
                  chat/chat-source (fn [room] {:room room})
                  messaging/get-channel-message!
                  (fn [& _] (delay {:content "hello"
                                   :author {:id "author" :username "alice"}}))
                  observers/find-all (constantly [observer])
                  handler/handle-raw
                  (fn [source user type bot info]
                    (obs/obs-handler
                      (assoc info :chat-source source :user user
                             :event-type type :yetibot-user bot)))
                  handler/record-and-run-raw
                  (fn [cmd & _] (deliver rendered cmd) [])]
      (discord/handle-event :message-reaction-add event
                            (atom {:rest :rest}) (atom {:id "bot"}))
      (deref rendered 3000 :timeout)
      => "!echo hello by alice via 💬 reaction from bob in C")))

(facts "missing gateway author IDs retain bot-message delete/ignore behavior"
  (doseq [emoji ["❌" "🍿"]]
    (let [deleted (atom [])]
      (with-redefs [messaging/get-channel-message!
                    (fn [& _] (delay {:author {:id "bot"}}))
                    messaging/delete-message!
                    (fn [& args] (swap! deleted conj args))
                    messaging/get-user!
                    (fn [& _] (throw (AssertionError. "Unneeded user lookup")))]
        (discord/handle-event :message-reaction-add
          {:message-id "M" :channel-id "C" :emoji {:name emoji}}
          (atom {:rest :rest}) (atom {:id "bot"}))
      (fact @deleted => (if (= emoji "❌") [[:rest "C" "M"]] []))))))

(facts
 "about message creation"
 (fact
  "ignore yetibot messages"
  (discord/handle-event :message-create
                        {:author {:id 123}}
                        (atom nil)
                        (atom {:id 123})) => nil)
 (fact
  "handles user messages"
  (discord/handle-event :message-create
                        {:author {:id 999 :username "fake"}
                         :channel-id 456
                         :content "fake content eh"}
                        (atom nil)
                        (atom {:id 123})) => "called handle-raw"
  (provided (users/create-user "fake" {:id 999 :username "fake"}) => {:username "fake"}
            (chat/chat-source 456) => {:channel-id 456 :room "fake"}
            (handler/handle-raw anything anything anything anything anything) => "called handle-raw")))

(facts
 "about generated-image-info"
 (fact
  "returns nil for messages without generated image URLs"
  (#'discord/generated-image-info "hello there!") => nil)
 (fact
  "extracts ID and extension when only URL is present"
  (#'discord/generated-image-info "http://localhost:3003/generated-images/fc62974c-0c61-4be6-8241-d1f79cd1eac0.png")
  => {:url "http://localhost:3003/generated-images/fc62974c-0c61-4be6-8241-d1f79cd1eac0.png"
      :id "fc62974c-0c61-4be6-8241-d1f79cd1eac0"
      :ext "png"})
 (fact
  "extracts ID and extension when URL is embedded in text"
  (#'discord/generated-image-info "Check out the celebration: http://localhost:3003/generated-images/fc62974c-0c61-4be6-8241-d1f79cd1eac0.png ! Very cool!")
  => {:url "http://localhost:3003/generated-images/fc62974c-0c61-4be6-8241-d1f79cd1eac0.png"
      :id "fc62974c-0c61-4be6-8241-d1f79cd1eac0"
      :ext "png"}))

(facts
 "about send-msg with generated images"
 (fact
  "sends message with attachment stream on discord and strips url"
  (with-redefs [images/image-store (atom {"fc62974c-0c61-4be6-8241-d1f79cd1eac0" {:data "SGVsbG8gd29ybGQ="}})]
    (binding [chat/*target* 456]
      (#'discord/send-msg {:conn (atom {:rest :fake-rest})}
                          "Check out the celebration: http://localhost:3003/generated-images/fc62974c-0c61-4be6-8241-d1f79cd1eac0.png ! Very cool!")))
  => "created-message-result"
  (provided (messaging/create-message! :fake-rest 456
                                      :content "Check out the celebration:  ! Very cool!"
                                      :stream anything) => (future "created-message-result")))
 (fact
  "sends message with attachment stream without content if text is blank"
  (with-redefs [images/image-store (atom {"fc62974c-0c61-4be6-8241-d1f79cd1eac0" {:data "SGVsbG8gd29ybGQ="}})]
    (binding [chat/*target* 456]
      (#'discord/send-msg {:conn (atom {:rest :fake-rest})}
                          "http://localhost:3003/generated-images/fc62974c-0c61-4be6-8241-d1f79cd1eac0.png")))
  => "created-message-result"
  (provided (messaging/create-message! :fake-rest 456
                                      :stream anything) => (future "created-message-result"))))

(defn- await-event [result]
  (when result
    (let [[value port] (async/alts!! [result (async/timeout 3000)])]
      (when-not (= port result)
        (throw (ex-info "Discord command did not finish" {})))
      value)))

(def command-message
  {:id "command" :channel-id "channel"
   :author {:id "alice" :username "alice"}
   :content "!echo before"
   :attachments [{:url "https://example.com/input.png"
                  :content-type "image/png"}]})

(defn- with-discord-session [evaluate f]
  (let [adapter (discord/make-discord {:name "test"})
        conn (:conn adapter)
        calls (atom [])
        next-id (atom 0)]
    (reset! conn {:rest :rest})
    (reset! (:yetibot-user adapter) {:id "bot"})
    (with-redefs [chat/chat-source (fn [room] {:adapter :discord :room room})
                  channel/settings-for-chat-source (constantly {})
                  observers/find-all (constantly [])
                  handler/record-and-run-raw
                  (fn [body user _]
                    (evaluate body user interp/*chat-source*))
                  messaging/create-message!
                  (fn [_ target & options]
                    (let [id (str (swap! next-id inc))
                          payload (apply hash-map options)]
                      (swap! calls conj [:create target id payload])
                      (delay {:id id :channel-id target})))
                  messaging/edit-message!
                  (fn [_ target id & options]
                    (swap! calls conj [:edit target id (apply hash-map options)])
                    (delay {:id id :channel-id target}))
                  messaging/delete-message!
                  (fn [_ target id]
                    (swap! calls conj [:delete target id])
                    (delay true))]
      (binding [chat/*adapter* adapter]
        (f (fn [type event]
             (discord/handle-event type event conn (:yetibot-user adapter)))
           calls conn)))))

(defn- echo-result [body _ _]
  [{:result (subs body 6)}])

(fact "editing a command updates its original reply and preserves input context"
  (let [contexts (atom [])]
    (with-discord-session
      (fn [body user source]
        (swap! contexts conj [body (:id user) (:raw-event source)])
        [{:result (subs body 6)}])
      (fn [event! calls _]
        (await-event (event! :message-create command-message))
        (await-event (event! :message-update
                            {:id "command" :channel-id "channel"
                             :content "!echo after"}))
        @calls => [[:create "channel" "1" {:content "before"}]
                   [:edit "channel" "1" {:content "after"}]]
        (mapv second @contexts) => ["alice" "alice"]
        (get-in @contexts [1 2 :attachments]) => (:attachments command-message)
        (get-in @contexts [1 2 :content]) => "!echo after"))))

(fact "embed-only, duplicate and bot updates never rerun a command"
  (with-discord-session echo-result
    (fn [event! calls _]
      (await-event (event! :message-create command-message))
      (doseq [update [{:embeds [{:title "Preview"}]}
                      {:content "!echo before"}
                      {:author {:id "bot"} :content "!echo loop"}]]
        (event! :message-update
                (merge {:id "command" :channel-id "channel"} update)))
      @calls => [[:create "channel" "1" {:content "before"}]])))

(fact "shorter edited replies remove obsolete Discord chunks"
  (with-discord-session echo-result
    (fn [event! calls _]
      (let [long-text (apply str (repeat 2100 "x"))]
        (await-event (event! :message-create
                            (assoc command-message :content (str "!echo " long-text))))
        (await-event (event! :message-update
                            {:id "command" :channel-id "channel"
                             :content "!echo short"}))
        (mapv #(subvec % 0 3) @calls)
        => [[:create "channel" "1"] [:create "channel" "2"]
            [:edit "channel" "1"] [:delete "channel" "2"]]
        (last (nth @calls 2)) => {:content "short"}))))

(fact "removing a command clears its replies without executing ordinary text"
  (with-discord-session echo-result
    (fn [event! calls _]
      (await-event (event! :message-create command-message))
      (await-event (event! :message-update
                          {:id "command" :channel-id "channel"
                           :content "never mind"}))
      @calls => [[:create "channel" "1" {:content "before"}]
                 [:delete "channel" "1"]])))

(fact "an edited command replaces the original error with the new result"
  (with-discord-session
    (fn [body _ _]
      [(if (= body "!echo before")
         {:error? true :result "Invalid input"}
         {:result "fixed"})])
    (fn [event! calls _]
      (await-event (event! :message-create command-message))
      (await-event (event! :message-update
                          {:id "command" :channel-id "channel"
                           :content "!echo fixed"}))
      @calls => [[:create "channel" "1" {:content "Invalid input"}]
                 [:edit "channel" "1" {:content "fixed"}]])))

(fact "generated-image replies are replaced along with their old attachments"
  (with-redefs [images/image-store
                (atom {"old" {:data "SGVsbG8="} "new" {:data "V29ybGQ="}})]
    (with-discord-session echo-result
      (fn [event! calls _]
        (await-event (event! :message-create
                            (assoc command-message :content
                                   "!echo https://bot.example/generated-images/old.png")))
        (await-event (event! :message-update
                            {:id "command" :channel-id "channel"
                             :content "!echo https://bot.example/generated-images/new.png"}))
        (mapv #(subvec % 0 3) @calls)
        => [[:create "channel" "1"] [:create "channel" "2"]
            [:delete "channel" "1"]]
        (get-in @calls [0 3 :stream :filename]) => "old.png"
        (get-in @calls [1 3 :stream :filename]) => "new.png"))))

(fact "editing into a command fetches the full Discord message when it is not cached"
  (let [fetched (atom [])]
    (with-discord-session echo-result
      (fn [event! calls _]
        (with-redefs [messaging/get-channel-message!
                      (fn [_ target id]
                        (swap! fetched conj [target id])
                        (delay (assoc command-message :content "ordinary message")))]
          (await-event (event! :message-update
                              {:id "command" :channel-id "channel"
                               :content "!echo new command"})))
        @fetched => [["channel" "command"]]
        @calls => [[:create "channel" "1" {:content "new command"}]]))))

(fact "a reply deleted by the user is recreated on the next command edit"
  (with-discord-session echo-result
    (fn [event! calls _]
      (await-event (event! :message-create command-message))
      (with-redefs [messaging/edit-message!
                    (fn [& _] (delay (ex-info "Unknown Message" {:code 10008})))]
        (await-event (event! :message-update
                            {:id "command" :channel-id "channel"
                             :content "!echo restored"})))
      @calls => [[:create "channel" "1" {:content "before"}]
                 [:create "channel" "2" {:content "restored"}]])))

(fact "reply tracking isolates commands in different channels"
  (with-discord-session echo-result
    (fn [event! calls _]
      (await-event (event! :message-create command-message))
      (await-event (event! :message-create
                          (assoc command-message :channel-id "elsewhere")))
      (await-event (event! :message-update
                          {:id "command" :channel-id "elsewhere"
                           :content "!echo changed"}))
      (mapv #(subvec % 0 3) @calls)
      => [[:create "channel" "1"] [:create "elsewhere" "2"]
          [:edit "elsewhere" "2"]])))

(fact "real echo commands rerun through the interpreter and update their reply"
  (let [run-raw handler/record-and-run-raw]
    (with-redefs [history/add (constantly nil)]
      (with-discord-session
        (fn [body user _] (run-raw body user {:id "bot" :username "yetibot"}))
        (fn [event! calls _]
          (await-event (event! :message-create command-message))
          (await-event (event! :message-update
                              {:id "command" :channel-id "channel"
                               :content "!echo after"}))
          @calls => [[:create "channel" "1" {:content "before"}]
                     [:edit "channel" "1" {:content "after"}]])))))

(fact "embedded command edits reconcile multiple replies"
  (with-discord-session
    (fn [body _ _]
      (mapv #(hash-map :result %)
            (if (= body "`echo one` `echo two`") ["one" "two"] ["three"])))
    (fn [event! calls _]
      (await-event (event! :message-create
                          (assoc command-message :content "`echo one` `echo two`")))
      (await-event (event! :message-update
                          {:id "command" :channel-id "channel"
                           :content "`echo three`"}))
      @calls => [[:create "channel" "1" {:content "one"}]
                 [:create "channel" "2" {:content "two"}]
                 [:edit "channel" "1" {:content "three"}]
                 [:delete "channel" "2"]])))

(fact "bounded tracking does not discard a command still running during eviction"
  (let [started (promise)
        release (promise)]
    (with-redefs-fn {#'discord/command-cache-size 1}
      (fn []
        (with-discord-session
          (fn [body _ _]
            (when (= body "!echo before") (deliver started true) @release)
            [{:result (subs body 6)}])
          (fn [event! calls conn]
            (let [original (event! :message-create command-message)]
              (try
                (deref started 3000 :timeout) => true
                (await-event (event! :message-create
                                    (assoc command-message :id "another"
                                           :content "!echo another")))
                (count (:commands @conn)) => 1
                (finally (deliver release true)))
              (await-event original)
              @calls => [[:create "channel" "1" {:content "another"}]
                         [:create "channel" "2" {:content "before"}]])))))))

(fact "eviction cannot let an obsolete revision overwrite an edited reply"
  (let [started (promise)
        release (promise)]
    (with-redefs-fn {#'discord/command-cache-size 1}
      (fn []
        (with-discord-session
          (fn [body _ _]
            (when (= body "!echo before") (deliver started true) @release)
            [{:result (subs body 6)}])
          (fn [event! calls _]
            (let [original (event! :message-create command-message)]
              (try
                (deref started 3000 :timeout) => true
                (await-event (event! :message-update
                                    {:id "command" :channel-id "channel"
                                     :content "!echo newest"}))
                (await-event (event! :message-create
                                    (assoc command-message :id "another"
                                           :content "!echo another")))
                (finally (deliver release true)))
              (await-event original)
              @calls => [[:create "channel" "1" {:content "newest"}]
                         [:create "channel" "2" {:content "another"}]])))))))

(fact "partial author objects retain the original username and attachments"
  (let [seen (atom [])]
    (with-discord-session
      (fn [body user source]
        (swap! seen conj [(:username user) (get-in source [:raw-event :attachments])])
        [{:result (subs body 6)}])
      (fn [event! _ _]
        (await-event (event! :message-create command-message))
        (await-event (event! :message-update
                            {:id "command" :channel-id "channel"
                             :author {:id "alice"} :content "!echo updated"}))
        (last @seen) => ["alice" (:attachments command-message)]))))

(fact "a pending Discord send does not block accepting an edit on the gateway"
  (let [sending (promise)
        release (promise)]
    (with-discord-session echo-result
      (fn [event! calls _]
        (let [create messaging/create-message!]
          (with-redefs [messaging/create-message!
                        (fn [& args]
                          (deliver sending true)
                          @release
                          (apply create args))]
            (let [original (event! :message-create command-message)
                  edited (promise)]
              (try
                (deref sending 3000 :timeout) => true
                (future (deliver edited
                                 (event! :message-update
                                         {:id "command" :channel-id "channel"
                                          :content "!echo newest"})))
                (not= :timeout (deref edited 1000 :timeout)) => true
                (finally (deliver release true)))
              (await-event original)
              (await-event (deref edited 3000 nil))
              @calls => [[:create "channel" "1" {:content "before"}]
                         [:edit "channel" "1" {:content "newest"}]])))))))

(fact "late completion of an old command cannot overwrite its edited result"
  (let [started (promise)
        release (promise)]
    (with-discord-session
      (fn [body _ _]
        (when (= body "!echo before")
          (deliver started true)
          @release)
        [{:result (subs body 6)}])
      (fn [event! calls _]
        (let [original (event! :message-create command-message)]
          (try
            (deref started 3000 :timeout) => true
            (await-event (event! :message-update
                                {:id "command" :channel-id "channel"
                                 :content "!echo newest"}))
            (finally (deliver release true)))
          (await-event original)
          @calls => [[:create "channel" "1" {:content "newest"}]])))))
