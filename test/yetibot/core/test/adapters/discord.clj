(ns yetibot.core.test.adapters.discord
  (:require             [yetibot.core.adapters.discord :as discord]
                        [discljord.messaging :as messaging]
                        [yetibot.core.handler :as handler]
                        [yetibot.core.models.users :as users]
                        [yetibot.core.chat :as chat]
                        [yetibot.core.commands.observe :as obs]
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
            