(ns yetibot.core.test.models.channel
  (:require [yetibot.core.models.channel :as chan]
            [midje.sweet :refer [=> every-checker fact facts contains]]))

(facts
 "about merge-defaults"
 (fact
  "does something awesome"
  (let [my-map {:config {:channel "merged"}}]
    (chan/merge-defaults my-map) =>
    (every-checker map?
                   (contains chan/channel-config-defaults)
                   (contains my-map)))))
