(ns yetibot.core.test.adapters.irc
  (:require [midje.sweet :refer [=> facts]]
            [yetibot.core.adapters.irc :refer [next-nick]]))

; example info args for handle-message
; - priv msg
; {:text !echo hi, :target yetibotz, :command PRIVMSG, :params [yetibotz !echo hi],
;  :raw :devth!~devth@1.1.1.1 PRIVMSG yetibotz :!echo hi, :host 2.2.2.2,
;  :user ~devth, :nick devth}
; - message from #yeti channel
; {:text !echo ook, :target #yeti, :command PRIVMSG, :params [#yeti !echo ook],
;  :raw :devth!~devth@1.1.1.1 PRIVMSG #yeti :!echo ook, :host 2.2.2.2,
;  :user ~devth, :nick devth}

(facts "next-nick should produce correct value"
  (next-nick "yetibot") => "yetibot_"
  (next-nick "yetibot_") => "yetibot__"
  (next-nick "yetibot__") => "yetibot_0"
  (next-nick "yetibot_0") => "yetibot_1"
  (next-nick "yetibot_9") => "yetibot00"
  (next-nick "999999999") => nil)
