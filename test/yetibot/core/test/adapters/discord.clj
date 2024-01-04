(ns yetibot.core.test.adapters.discord
  (:require             [yetibot.core.adapters.discord :as discord]
                        [discljord.messaging :as messaging]
                        [yetibot.core.handler :as handler]
                        [yetibot.core.models.users :as users]
                        [yetibot.core.chat :as chat]
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



            