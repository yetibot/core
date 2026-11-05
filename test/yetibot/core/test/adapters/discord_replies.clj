(ns yetibot.core.test.adapters.discord-replies
  (:require [midje.sweet :refer [fact facts =>]]
            [discljord.messaging :as messaging]
            [yetibot.core.adapters.discord :as discord]))

(facts "ordinary command answers use native replies without mentions"
  (doseq [file? [false true]]
    (let [calls (atom [])
          part (cond-> {:content "answer" :command-key ["100" "200"]}
                 file? (assoc :file "full answer" :filename "answer.md"))]
      (with-redefs [messaging/create-message!
                    (fn [_ channel & options]
                      (swap! calls conj [channel (apply hash-map options)])
                      (doto (promise) (deliver {:id "300"}))) ]
        (#'discord/create-message! (atom {:rest :connection}) "100" part)
        (let [[channel options] (first @calls)]
          channel => "100"
          (:message-reference options)
          => {:message_id "200" :channel_id "100"
              :fail_if_not_exists false}
          (:allowed-mentions options) => {:parse [] :replied_user false}
          (:flags options) => 32768
          (boolean (:stream options)) => file?
          (count @calls) => 1)))))

(facts "cross-channel and explicit new-thread delivery never reference a parent"
  (doseq [part [{:content "plain send"}
                {:content "thread answer" :command-key ["parent" "200"]}]]
    (let [calls (atom [])]
      (with-redefs [messaging/create-message!
                    (fn [_ _ & options]
                      (swap! calls conj (apply hash-map options))
                      (doto (promise) (deliver {:id "300"})))]
        (#'discord/create-message! (atom {:rest :connection}) "100" part)
        (:message-reference (first @calls)) => nil
        (count @calls) => 1))))
