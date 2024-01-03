(ns yetibot.core.test.adapters.discord
  (:require [midje.sweet :refer [fact facts provided anything =>]]
            [discljord.messaging :as messaging]
            [yetibot.core.adapters.discord :as discord]))

(facts "about handle-event :message-reaction-add"
       (fact "deletes yetibot message when reacted with ❌"
             (provided (messaging/delete-message! anything anything anything) => "I did it"
                       (discord/handle-event :message-reaction-add :message-reaction-add #{"message-id" 123
                                                                                   "channel-id" 456
                                                                                   "message-author-id" 111
                                                                                   "emoji" #{"name" "❌"}}
                                     (atom nil)
                                     (atom 111)) => "I did it")))
             