(ns yetibot.core.test.adapters.discord
  (:require             [yetibot.core.adapters.discord :as discord]
                        [discljord.messaging :as messaging]
                        [yetibot.core.handler :as handler]
                        [yetibot.core.models.users :as users]
                        [yetibot.core.chat :as chat]
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
                        (atom {:id 111})) => nil)
 (fact
  "when reacting to a non delete user message handle-raw is called"
  (let [mock-promise (def x (promise))]
    (discord/handle-event :message-reaction-add
                          {:message-id 123
                           :channel-id 456
                           :message-author-id 999
                           :emoji {:name "🍿"}}
                          (atom nil)
                          (atom {:id 111})) => "called handle-raw"
    (provided (handler/handle-raw anything anything anything anything anything) => "called handle-raw"
              (chat/chat-source 456) => {:channel-id 456 :room "fake"}
              (users/get-user anything anything) => {:id 888}
              (messaging/get-channel-message! anything 456 123) => (deliver mock-promise "fake content")))))


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
            