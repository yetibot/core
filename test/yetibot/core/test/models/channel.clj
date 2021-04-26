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
  "does something"
  (let [cmap {:uuid 123 :room "#abc123"}]
    (chan/settings-for-chat-source cmap) => true
    (provided (chan/channel-settings (:uuid cmap) (:room cmap)) => true))))
