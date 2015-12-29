(ns yetibot.core.test.adapters.irc
  (:require
    [yetibot.core.adapters.init :as ai]
    [yetibot.core.adapters.irc :refer :all]
    [yetibot.core.chat :as chat]
    [clojure.test :refer :all]))

; example info args for handle-message
; - priv msg
; {:text !echo hi, :target yetibotz, :command PRIVMSG, :params [yetibotz !echo hi],
;  :raw :devth!~devth@1.1.1.1 PRIVMSG yetibotz :!echo hi, :host 2.2.2.2,
;  :user ~devth, :nick devth}
; - message from #yeti channel
; {:text !echo ook, :target #yeti, :command PRIVMSG, :params [#yeti !echo ook],
;  :raw :devth!~devth@1.1.1.1 PRIVMSG #yeti :!echo ook, :host 2.2.2.2,
;  :user ~devth, :nick devth}

(defn irc-config []
  (filter
    (fn [c] (= :irc (:type c)))
    (ai/adapters-config)))

(deftest rooms-for-last-config
  (comment

    (binding [*config* (last (irc-config))]
      (rooms))

    (binding [*config* (last (irc-config))]
      (:groups (list-groups)))

    ))
