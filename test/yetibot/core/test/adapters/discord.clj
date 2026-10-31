(ns yetibot.core.test.adapters.discord
  (:require             [clojure.core.async :as async]
                        [clojure.string :as str]
                        [yetibot.core.adapters.discord :as discord]
                        [yetibot.core.test.adapters.discord-payload :as payload]
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

(facts "Discord react accepts Unicode and static/animated custom emoji"
  (doseq [[emoji encoded] [["<:party:123456789012345678>" "party%3A123456789012345678"]
                           ["<a:dance:123456789012345678>" "dance%3A123456789012345678"]
                           ["party:123456789012345678" "party%3A123456789012345678"]
                           ["👍" "%F0%9F%91%8D"]
                           ["ℹ️" "%E2%84%B9%EF%B8%8F"]
                           ["‼️" "%E2%80%BC%EF%B8%8F"]
                           ["⁉️" "%E2%81%89%EF%B8%8F"]
                           ["〰️" "%E3%80%B0%EF%B8%8F"]
                           ["〽️" "%E3%80%BD%EF%B8%8F"]
                           ["🫩" "%F0%9F%AB%A9"]
                           ["#️⃣" "%23%EF%B8%8F%E2%83%A3"]]]
    (let [calls (atom [])
          adapter {:conn (atom {:rest :fixture}) :yetibot-user (atom {:id "BOT"})}]
      (with-redefs [messaging/get-channel-messages!
                    (fn [& _] (doto (promise)
                                (deliver [{:id "bot" :author {:id "BOT"} :content "result"}
                                          {:id "command" :author {:id "U"} :content "!react emoji"}
                                          {:id "target" :author {:id "U"} :content "hello"}])))
                    messaging/create-reaction!
                    (fn [& args] (swap! calls conj args)
                      (doto (promise) (deliver true)))]
        (fact (discord/react adapter emoji "C") => true)
        (fact @calls => [(list :fixture "C" "target" encoded)])))))

(fact "Unicode subdivision flags retain their emoji tag characters"
  (let [flag (str "🏴" "\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65"
                       "\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F")]
    (discord/reaction-emoji flag) => flag))

(fact "Discord explicit-message reactions never select a different target"
  (let [calls (atom []) adapter {:conn (atom {:rest :fixture})}]
    (with-redefs [messaging/get-channel-messages!
                  (fn [& _] (throw (AssertionError. "No history lookup")))
                  messaging/create-reaction!
                  (fn [& args] (swap! calls conj args)
                    (doto (promise) (deliver true)))]
      (discord/react adapter "<:party:123456789012345678>" "C" "M") => true
      @calls => [(list :fixture "C" "M" "party%3A123456789012345678")])))

(facts "invalid reaction syntax fails before any Discord request"
  (doseq [emoji ["" ":party:" "party" "<:party:bad-id>" "👍/../messages" "👍\n"]]
    (let [calls (atom 0)]
      (with-redefs [messaging/get-channel-messages! (fn [& _] (swap! calls inc))
                    messaging/create-reaction! (fn [& _] (swap! calls inc))]
        (fact (try (discord/react {:conn (atom {})} emoji "C") false
                   (catch Exception _ true)) => true)
        (fact @calls => 0)))))

(fact "bot-added reactions cannot delete messages or retrigger observers"
  (doseq [author-id ["BOT" "human" nil]]
    (with-redefs [messaging/delete-message!
                  (fn [& _] (throw (AssertionError. "No reaction-triggered delete")))
                  messaging/get-channel-message!
                  (fn [& _] (throw (AssertionError. "No self-reaction lookup")))
                  handler/handle-raw
                  (fn [& _] (throw (AssertionError. "No observer feedback loop")))]
      (fact (discord/handle-event :message-reaction-add
              (cond-> {:message-id "M" :channel-id "C" :user-id "BOT"
                       :emoji {:name "❌"}}
                author-id (assoc :message-author-id author-id))
              (atom {:rest :fixture}) (atom {:id "BOT"})) => nil))))

(fact "reaction rejection and timeout never report success"
  (doseq [[response unknown?]
          [[(doto (promise) (deliver false)) false]
           [(reify clojure.lang.IBlockingDeref
              (deref [_ _ timeout-value] timeout-value)) true]]]
    (with-redefs [messaging/create-reaction! (fn [& _] response)]
      (try (discord/react {:conn (atom {:rest :fixture})} "👍" "C" "M")
           :unexpected-success
           (catch Exception e (:discord/reaction-unknown? (ex-data e))))
      => unknown?)))

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
                                      :flags 32768 :components anything
                                      :allowed-mentions {:parse [] :replied_user false}
                                      :stream anything) => (future "created-message-result")))
 (fact
  "sends message with attachment stream without content if text is blank"
  (with-redefs [images/image-store (atom {"fc62974c-0c61-4be6-8241-d1f79cd1eac0" {:data "SGVsbG8gd29ybGQ="}})]
    (binding [chat/*target* 456]
      (#'discord/send-msg {:conn (atom {:rest :fake-rest})}
                          "http://localhost:3003/generated-images/fc62974c-0c61-4be6-8241-d1f79cd1eac0.png")))
  => "created-message-result"
  (provided (messaging/create-message! :fake-rest 456
                                      :flags 32768 :components anything
                                      :allowed-mentions {:parse [] :replied_user false}
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
    (with-redefs [users/users (atom {})
                  chat/chat-source (fn [room] {:adapter :discord :room room})
                  channel/settings-for-chat-source (constantly {})
                  observers/find-all (constantly [])
                  handler/record-and-run-raw
                  (fn [body user _]
                    (evaluate body user interp/*chat-source*))
                  messaging/create-message!
                  (fn [_ target & options]
                    (let [id (str (swap! next-id inc))
                          payload (payload/logical (apply hash-map options))]
                      (swap! calls conj [:create target id payload])
                      (deliver (promise) {:id id :channel-id target})))
                  messaging/edit-message!
                  (fn [_ target id & options]
                    (swap! calls conj [:edit target id (payload/logical (apply hash-map options))])
                    (deliver (promise) {:id id :channel-id target}))
                  messaging/delete-message!
                  (fn [_ target id]
                    (swap! calls conj [:delete target id])
                    (deliver (promise) true))]
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

(fact "shorter edited replies remove obsolete separate Discord replies"
  (with-discord-session
    (fn [body _ _]
      (doseq [text (if (= body "!echo short") ["short"] ["first" "second"])]
        (chat/send-msg text)))
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

;; Resolve at runtime so the proof-first tests can exercise the missing API.
(defn- publish-output! [target chunks]
  (if-let [publish (ns-resolve 'yetibot.core.adapters.discord
                              'publish-command-output!)]
    (publish target chunks)
    :missing-api))

(defn- live-part [content]
  {:content content :allowed-mentions {:parse []}})

(defn- live-edit [content]
  {:content content :allowed_mentions {:parse []}})

(fact "live answers append before completion and replay without duplicates"
  (let [published (promise)
        release (promise)]
    (with-discord-session
      (fn [_ _ _]
        (deliver published [(publish-output! "thread" ["first @everyone"])
                            (publish-output! "thread" ["second" "third"])])
        @release
        [])
      (fn [event! calls conn]
        (let [completion (event! :message-create command-message)]
          (try
            (deref published 3000 :timeout) => [:published :published]
            ;; The command is still paused: these must be actual REST creates.
            @calls => [[:create "thread" "1" (live-part "first @everyone")]
                       [:create "thread" "2" (live-part "second")]
                       [:create "thread" "3" (live-part "third")]]
            (finally (deliver release true)))
          (await-event completion)
          @calls => [[:create "thread" "1" (live-part "first @everyone")]
                     [:create "thread" "2" (live-part "second")]
                     [:create "thread" "3" (live-part "third")]
                     [:edit "thread" "1" (live-edit "first @everyone")]
                     [:edit "thread" "2" (live-edit "second")]
                     [:edit "thread" "3" (live-edit "third")]]
          @(get-in @conn [:commands ["channel" "command"] :replies])
          => [{:id "1" :target "thread" :image? false}
              {:id "2" :target "thread" :image? false}
              {:id "3" :target "thread" :image? false}])))))

(fact "live edits reuse IDs, suppress mentions, and remove obsolete chunks"
  (with-discord-session
    (fn [body _ _]
      (publish-output! "thread"
                       (if (= body "!echo before") ["old" "extra"] ["new"]))
      [])
    (fn [event! calls _]
      (await-event (event! :message-create command-message))
      (reset! calls [])
      (await-event (event! :message-update
                          {:id "command" :channel-id "channel"
                           :content "!echo new"}))
      @calls => [[:edit "thread" "1" (live-edit "new")]
                 [:edit "thread" "1" (live-edit "new")]
                 [:delete "thread" "2"]])))

(fact "old bound callbacks cannot publish after a newer revision or eviction"
  (let [started (promise)
        release (promise)
        status (promise)]
    (with-redefs-fn {#'discord/command-cache-size 1}
      (fn []
        (with-discord-session
          (fn [body _ _]
            (if (= body "!echo before")
              (do (deliver started true)
                  @release
                  (deliver status (publish-output! "thread" ["obsolete"])))
              (publish-output! "thread" [(subs body 6)]))
            [])
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
                (reset! calls [])
                (finally (deliver release true)))
              (await-event original)
              (deref status 3000 :timeout) => :stale
              @calls => [])))))))

(fact "outside command context publication is unavailable without IO"
  (with-discord-session echo-result
    (fn [_ calls _]
      (publish-output! "thread" ["standalone"]) => :unavailable
      @calls => [])))

(fact "invalid live chunks fail statically before IO or output mutation"
  (let [errors (atom [])]
    (with-discord-session
      (fn [_ _ _]
        (doseq [chunks [nil '("secret") [nil] [42] [{:image "secret"}]
                        [(str/join (repeat 4001 "s"))]
                        (vec (repeat 31 "secret"))]]
          (swap! errors conj
                 (try (publish-output! "thread" chunks)
                      :no-error
                      (catch IllegalArgumentException e (.getMessage e)))))
        (publish-output! "thread" [(str/join (repeat 4000 "x"))])
        [])
      (fn [event! calls _]
        (await-event (event! :message-create command-message))
        @errors => (vec (repeat 7 "Invalid Discord command output chunks"))
        (mapv first @calls) => [:create :edit]
        (count (get-in @calls [0 3 :content])) => 4000))))

(fact "live output preserves buffered indices without sending the prefix early"
  (let [published (promise)
        release (promise)]
    (with-discord-session
      (fn [_ _ _]
        (#'discord/send-msg chat/*adapter* "buffered")
        (deliver published (publish-output! "thread" ["live"]))
        @release
        [{:result "terminal"}])
      (fn [event! calls conn]
        (let [completion (event! :message-create command-message)]
          (try
            (deref published 3000 :timeout) => :published
            @calls => [[:create "thread" "1" (live-part "live")]]
            (finally (deliver release true)))
          (await-event completion)
          @calls => [[:create "thread" "1" (live-part "live")]
                     [:create "channel" "2" {:content "buffered"}]
                     [:edit "thread" "1" (live-edit "live")]
                     [:create "channel" "3" {:content "terminal"}]]
          (mapv :id @(get-in @conn [:commands ["channel" "command"] :replies]))
          => ["2" "1" "3"])))))

(fact "live target changes and missing messages use shared replacement"
  (with-discord-session
    (fn [body _ _]
      (publish-output! (if (= body "!echo before") "old-thread" "new-thread")
                       ["live"])
      [])
    (fn [event! calls _]
      (await-event (event! :message-create command-message))
      (reset! calls [])
      (await-event (event! :message-update
                          {:id "command" :channel-id "channel"
                           :content "!echo moved"}))
      @calls => [[:create "new-thread" "2" (live-part "live")]
                 [:delete "old-thread" "1"]
                 [:edit "new-thread" "2" (live-edit "live")]]
      (reset! calls [])
      (let [edit messaging/edit-message!]
        (with-redefs [messaging/edit-message!
                      (fn [rest target id & options]
                        (if (= id "2")
                          (deliver (promise) {:code 10008})
                          (apply edit rest target id options)))]
          (await-event (event! :message-update
                              {:id "command" :channel-id "channel"
                               :content "!echo missing"}))))
      @calls => [[:create "new-thread" "3" (live-part "live")]
                 [:edit "new-thread" "3" (live-edit "live")]])))

(fact "eviction before edit shares ownership until every generation retires"
  (let [started (promise)
        release (promise)
        status (promise)]
    (with-redefs-fn {#'discord/command-cache-size 1}
      (fn []
        (with-discord-session
          (fn [body _ _]
            (if (= body "!echo before")
              (do (deliver started true)
                  @release
                  (deliver status (publish-output! "thread" ["obsolete"])))
              (publish-output! "thread" [(subs body 6)]))
            [])
          (fn [event! calls conn]
            (with-redefs [messaging/get-channel-message!
                          (fn [& _] (delay command-message))]
              (let [original (event! :message-create command-message)]
                (try
                  (deref started 3000 :timeout) => true
                  (await-event
                    (event! :message-create
                            (assoc command-message :id "other"
                                   :content "!echo other")))
                  (get-in @conn [:commands ["channel" "command"]]) => nil
                  (await-event
                    (event! :message-update
                            {:id "command" :channel-id "channel"
                             :content "!echo newest"}))
                  ;; Newer finished first: original still needs its ownership.
                  (count (:active-commands @conn)) => 1
                  (set (keys (get-in @conn
                                    [:active-commands ["channel" "command"]])))
                  => #{:revision :lock :replies :running :details :confirmation}
                  (await-event
                    (event! :message-create
                            (assoc command-message :id "last"
                                   :content "!echo last")))
                  (await-event
                    (event! :message-update
                            {:id "command" :channel-id "channel"
                             :content "!echo final"}))
                  (get-in (last @calls) [3 :content]) => "final"
                  (reset! calls [])
                  (finally (deliver release true)))
                (await-event original)
                (deref status 3000 :timeout) => :stale
                @calls => []
                (:active-commands @conn) => {}))))))))

(fact "older retirement cannot remove newer active ownership after eviction"
  (let [started (promise)
        old-release (promise)
        new-started (promise)
        new-release (promise)
        new-status (promise)]
    (with-redefs-fn {#'discord/command-cache-size 1}
      (fn []
        (with-discord-session
          (fn [body _ _]
            (case body
              "!echo before" (do (deliver started true) @old-release)
              "!echo pending" (do (deliver new-started true)
                                  @new-release
                                  (deliver new-status
                                           (publish-output! "thread" ["old"])))
              (publish-output! "thread" [(subs body 6)]))
            [])
          (fn [event! calls conn]
            (let [original (event! :message-create command-message)]
              (try
                (deref started 3000 :timeout) => true
                (let [newer (event! :message-update
                                    {:id "command" :channel-id "channel"
                                     :content "!echo pending"})]
                  (try
                    (deref new-started 3000 :timeout) => true
                    (deliver old-release true)
                    (await-event original)
                    (count (:active-commands @conn)) => 1
                    (await-event
                      (event! :message-create
                              (assoc command-message :id "other"
                                     :content "!echo other")))
                    (with-redefs [messaging/get-channel-message!
                                  (fn [& _] (delay command-message))]
                      (await-event
                        (event! :message-update
                                {:id "command" :channel-id "channel"
                                 :content "!echo latest"})))
                    (reset! calls [])
                    (finally (deliver new-release true)))
                  (await-event newer)
                  (deref new-status 3000 :timeout) => :stale
                  @calls => []
                  (:active-commands @conn) => {})
                (finally
                  (deliver old-release true)
                  (deliver new-release true))))))))))

(fact "retired callbacks stay stale and active metadata cleans up on errors"
  (let [callback (promise)]
    (with-discord-session
      (fn [_ _ _]
        (deliver callback (bound-fn [] (publish-output! "thread" ["late"])))
        [])
      (fn [event! calls conn]
        (await-event (event! :message-create command-message))
        ((deref callback 3000 (constantly :missing))) => :stale
        @calls => []
        (:active-commands @conn) => {}
        (with-redefs [handler/handle-raw
                      (fn [& _] (throw (Exception. "dispatch failure")))]
          (try (event! :message-create command-message)
               (catch Exception _ :failed)) => :failed)
        (:active-commands @conn) => {})))
  (with-discord-session echo-result
    (fn [event! _ conn]
      (with-redefs [messaging/create-message!
                    (fn [& _] (deliver (promise) nil))]
        (await-event (event! :message-create command-message)))
      (:active-commands @conn) => {})))

(defn- with-short-live-deadline [f]
  ;; Resolve the override only when implemented, allowing a real baseline RED.
  (if-let [timeout-var (ns-resolve 'yetibot.core.adapters.discord
                                 'live-delivery-timeout-ms)]
    (with-redefs-fn {timeout-var 40} f)
    (f)))

(defn- delivery-error [f]
  (try (f)
       :no-error
       (catch Exception e
         [(.getMessage e) (ex-data e) (.getCause e)])))

(facts "failed partial live creates retain confirmed IDs without replaying gaps"
  (doseq [response [{:code 50013} nil :throw]]
    (let [status (promise)]
      (with-discord-session
        (fn [_ _ _]
          (deliver status
                   (delivery-error
                     #(publish-output! "thread" ["first" "failed" "unsent"])))
          [{:result "later"}])
        (fn [event! calls conn]
          (let [create messaging/create-message!
                attempt (atom 0)]
            (with-redefs [messaging/create-message!
                          (fn [& args]
                            (let [result (apply create args)]
                              (if (= 2 (swap! attempt inc))
                                (if (= response :throw)
                                  (throw (ex-info "secret" {:secret "body"}))
                                  (deliver (promise) response))
                                result)))]
              (await-event (event! :message-create command-message))))
          (deref status 3000 :timeout)
          => ["Discord live delivery failed" {} nil]
          @calls => [[:create "thread" "1" (live-part "first")]
                     [:create "thread" "2" (live-part "failed")]
                     [:edit "thread" "1" (live-edit "first")]
                     [:create "channel" "3" {:content "later"}]]
          @(get-in @conn [:commands ["channel" "command"] :replies])
          => [{:id "1" :target "thread" :image? false}
              nil nil {:id "3" :target "channel" :image? false}]
          (:active-commands @conn) => {})))))

(fact "unresolved live create times out without resending or holding the lock"
  (let [held (promise)
        status (promise)
        started (promise)]
    (with-short-live-deadline
      (fn []
        (with-discord-session
          (fn [body _ _]
            (if (= body "!echo before")
              (do (deliver status
                           (delivery-error
                             #(publish-output! "thread" ["first" "unknown"
                                                         "unsent"])))
                  [{:result "later"}])
              [{:result "newer"}]))
          (fn [event! calls conn]
            (let [create messaging/create-message!
                  attempt (atom 0)]
              (with-redefs [messaging/create-message!
                            (fn [& args]
                              (let [result (apply create args)]
                                (if (= 2 (swap! attempt inc))
                                  (do (deliver started true) held)
                                  result)))]
                (let [original (event! :message-create command-message)
                      newer (atom nil)]
                  (try
                    (deref started 3000 :timeout) => true
                    (deref status 500 :timeout)
                    => ["Discord live delivery failed" {} nil]
                    (let [[_ port]
                          (async/alts!! [original (async/timeout 500)])]
                      (= port original) => true)
                    @calls => [[:create "thread" "1" (live-part "first")]
                               [:create "thread" "2" (live-part "unknown")]
                               [:edit "thread" "1" (live-edit "first")]
                               [:create "channel" "3" {:content "later"}]]
                    (reset! calls [])
                    (reset! newer
                            (event! :message-update
                                    {:id "command" :channel-id "channel"
                                     :content "!echo newer"}))
                    (let [[_ port]
                          (async/alts!! [@newer (async/timeout 500)])]
                      (= port @newer) => true)
                    ;; Both generations finish while the REST value is held.
                    (realized? held) => false
                    (mapv first @calls) => [:create :delete :delete]
                    (:active-commands @conn) => {}
                    (finally (deliver held {:id "late-unknown"})))
                  (await-event original)
                  (await-event @newer))))))))))

(facts "live edit and delete waits and terminal replay are bounded"
  (doseq [operation [:edit :delete :terminal]]
    (let [held (promise)
          status (atom nil)
          entered (promise)]
      (with-short-live-deadline
        (fn []
          (with-discord-session
            (fn [body _ _]
              (reset! status
                      (delivery-error
                        #(publish-output!
                           (if (= body "!echo before") "old" "new")
                           ["live"])))
              [])
            (fn [event! calls conn]
              (when (not= operation :terminal)
                (await-event (event! :message-create command-message)))
              (let [edit messaging/edit-message!
                    delete messaging/delete-message!
                    block (fn [] (deliver entered true) held)]
                (with-redefs [messaging/edit-message!
                              (fn [& args]
                                (if (= operation :terminal)
                                  (block)
                                  (apply edit args)))
                              messaging/delete-message!
                              (fn [& args]
                                (if (= operation :delete)
                                  (block)
                                  (apply delete args)))]
                  ;; :edit uses the same target, :delete changes the target.
                  (let [target (if (= operation :edit) "old" "new")
                        publish (ns-resolve 'yetibot.core.adapters.discord
                                            'publish-command-output!)
                        original-publish @publish]
                    (with-redefs-fn
                      (cond-> {}
                        (= operation :edit)
                        (assoc #'messaging/edit-message!
                               (fn [& _] (block))
                               publish (fn [_ chunks]
                                         (original-publish target chunks))))
                      (fn []
                        (let [completion
                              (event! (if (= operation :terminal)
                                        :message-create :message-update)
                                      (assoc command-message
                                             :content "!echo next"))]
                          (try
                            (deref entered 3000 :timeout) => true
                            (let [[_ port]
                                  (async/alts!! [completion
                                                 (async/timeout 500)])]
                              (= port completion) => true)
                            (when-not (= operation :terminal)
                              @status => ["Discord live delivery failed"
                                          {} nil])
                            (finally (deliver held {:id "late"})))
                          (await-event completion)
                          (:active-commands @conn) => {})))))))))))))

(fact "one live batch shares its remaining deadline across REST operations"
  (let [waits (atom [])
        ids (atom 0)]
    (with-redefs-fn
      {(ns-resolve 'yetibot.core.adapters.discord
                   'live-delivery-timeout-ms) 200}
      (fn []
        (with-discord-session
          (fn [_ _ _]
            (publish-output! "thread" ["first" "second"]) => :published
            [])
          (fn [event! _ _]
            (with-redefs [messaging/create-message!
                          (fn [& _]
                            (let [id (str (swap! ids inc))]
                              (reify
                                clojure.lang.IDeref
                                (deref [_] {:id id})
                                clojure.lang.IBlockingDeref
                                (deref [_ timeout-ms _]
                                  (swap! waits conj timeout-ms)
                                  (when (= id "1") (Thread/sleep 40))
                                  {:id id}))))]
              (await-event (event! :message-create command-message)))
            (count @waits) => 2
            (< (get @waits 1 1000) (- (get @waits 0 0) 20)) => true))))))
