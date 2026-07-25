(ns yetibot.core.test.webapp.routes.api
  (:require [yetibot.core.webapp.routes.api :refer [api]]
            [yetibot.core.chat :refer [chat-data-structure]]
            [yetibot.core.handler :refer [handle-unparsed-expr]]
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
    (provided (chat-data-structure anything) => nil)))

 (let [command-cs {:chat-source "{:uuid \"C123\" :room \"#mychan\"}"
                   :command "echo hello"}
       req "/api"]
   (fact
    "will evaluate command and return its extracted :value"
    (api command-cs req) => "hello from command"
    (provided
      (handle-unparsed-expr anything anything "echo hello") => {:settings {} :skip-next-n 0 :value "hello from command" :data nil}
      (chat-data-structure "hello from command") => nil))

   (fact
    "will evaluate command and return its extracted :error on failure"
    (api command-cs req) => "error occurred"
    (provided
      (handle-unparsed-expr anything anything "echo hello") => {:error "error occurred"}
      (chat-data-structure "error occurred") => nil)))

 (let [agent-cs {:chat-source "{:adapter :agent :room \"agent-room\"}"
                 :command "echo hello"}
       req "/api"]
   (fact
    "will evaluate command but NOT call chat-data-structure when chat-source is for agent-room / has no :uuid"
    (api agent-cs req) => "hello from command"
    (provided
      (handle-unparsed-expr anything anything "echo hello") => {:settings {} :skip-next-n 0 :value "hello from command" :data nil}
      (chat-data-structure "hello from command") => nil :times 0))))
