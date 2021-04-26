(ns yetibot.core.test.models.channel
  (:require [yetibot.core.models.channel :as chan]
            [yetibot.core.db.channel :as db]
            [midje.sweet :refer [=> every-checker fact facts contains
                                 provided anything just]]))

(facts
 "about merge-defaults"
 (fact
  "merges default channel configs and whatever map is passed to it"
  (let [my-map {:config {:channel "merged"}}]
    (chan/merge-defaults my-map) =>
    (every-checker map?
                   (contains chan/channel-config-defaults)
                   (contains my-map)))))

(facts
 "about channel-settings"
 (fact
  "returns merged map of results from db and default channel configs"
  (let [db-result {:key 123 :value "abc123"}]
    (chan/channel-settings "abc123" "#mychan") =>
    (every-checker map?
                   (contains chan/channel-config-defaults)
                   (contains {(:key db-result) (:value db-result)}))
    (provided (db/query anything) => [db-result])))
 (fact
  "returns only default channel configs when DB results are empty"
  (chan/channel-settings "abc123" "#mychan") => (just chan/channel-config-defaults)
  (provided (db/query anything) => [])))

(facts
 "about settings-for-chat-source"
 (fact
  "simply parses the map param and extracts correct keys to call channel-settings,
   we are verifyig the function is called with the correct extracted params"
  (let [cmap {:uuid 123 :room "#abc123"}]
    (chan/settings-for-chat-source cmap) => true
    (provided (chan/channel-settings (:uuid cmap) (:room cmap)) => true))))

(facts
 "about find-key"
 (fact
  "gets the first result from a db query using the defined params, we are verifying
   the db/query function is called with the correct params"
  (let [uuid :someuuid
        channel "#somechannel"
        adapt-key "somekey"
        key-result 123
        query-wo-channel {:where/map {:chat-source-adapter (pr-str uuid)
                                      :key adapt-key}}
        query-w-channel (assoc-in query-wo-channel
                                  [:where/map :chat-source-channel]
                                  channel)]
    ;; when no channel is present
    (chan/find-key uuid nil adapt-key) => key-result
    (provided (db/query query-wo-channel) => [key-result])
    ;; when channel is real
    (chan/find-key uuid channel adapt-key) => key-result
    (provided (db/query query-w-channel) => [key-result]))))
