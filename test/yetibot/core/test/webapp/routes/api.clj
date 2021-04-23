(ns yetibot.core.test.webapp.routes.api
  (:require [yetibot.core.webapp.routes.api :refer [api]]
            [yetibot.core.chat :refer [chat-data-structure]]
            [midje.sweet :refer [=> fact facts contains provided anything]]))

(facts
 "about api"
 (fact
  "will return string complaining about missing required chat-source param when
   is empty/missing"
  (api {:chat-source ""} "/api") => (contains "required"))
 (fact
  "will return string complaining about missing required command/text params when
   is empty/missing"
  (api {:command "" :text ""} "/api") => (contains "required"))
 (let [good-cs {:chat-source "{:uuid \"C123\" :room \"#mychan\"}"
                :command "echo hello"
                :text "some text"}
       req "/api"]
   (fact
    "will return :text when :chat-source is legit, which is almost always
     as long as it is not empty/nil and not malformed"
    (api good-cs req) => (:text good-cs)
    (provided (chat-data-structure anything) => nil))))
