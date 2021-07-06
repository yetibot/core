(ns yetibot.core.test.handler
  (:require
   [midje.sweet :refer [facts fact => =not=> contains]]
   [yetibot.core.handler :as h]
   [clojure.string :as s]
   [yetibot.core.interpreter :as i]
   [yetibot.core.commands.echo]))

(comment
  ;; generate some history
  (dotimes [i 10]
    (h/handle-raw
     {:adapter :test :room "foo"}
     {:id "yetitest"}
     :message
     {:username "yetibot" :id "123"}
     {:body (str "test history: " i)})))

(def multiline-str (s/join \newline [1 2 3]))

(fact
 "Newlines are preserved in command handling"
 ;;  (ldr/load-commands)
 (:value (h/handle-unparsed-expr (str "echo " multiline-str))) => multiline-str)

(facts
 "about ->correlation-id"
 (binding [i/*chat-source* {:adapter :slack
                            :uuid :test
                            :room "#C123"}]
   (let [user {:id 123 :username "greg" :yetibot? false}]
     (fact
      "it will return an id of numbers and hyphens"
      (h/->correlation-id "!echo hello" user) => #"[0-9]+[-]+[0-9]+")

     (let [inst1 (h/->correlation-id "!echo hello" user)
           _ (Thread/sleep 1000)
           inst2 (h/->correlation-id "!echo hello" user)]
       (fact
        "the id is unique, even when the user and message body are the same
         because it incorporates a timestamp"
        inst1 =not=> inst2)))))

(facts
 "about ->parsed-message-info"
 (binding [i/*chat-source* {:adapter :slack
                            :uuid :test
                            :room "#C123"}]
   (fact
    "it recognizes a command and has command related info"
    (h/->parsed-message-info "!echo hello")
    => (contains {:cmd? true
                  :parsed-cmds coll?
                  :parsed-normal-command coll?}))

   (fact
    "it recognizes a non-command and has non-command related info"
    (h/->parsed-message-info "i am not a command")
    => (contains {:cmd? false
                  :parsed-cmds empty? ; is an empty list
                  :parsed-normal-command nil}))))

(facts
 "about add-user-message-history"
 (let [body "!echo hello"
       user {:id 123 :username "greg" :yetibot? false}
       correlation-id (h/->correlation-id body user)
       cs {:adapter :slack :uuid :test :room "#C123"}]
   (binding [i/*chat-source* cs]
     (fact
      "it will add to the history table a user message, with related message
       metadata"
      (first (h/add-user-message-history body user correlation-id))
      => (contains {:body body
                    :chat_source_room (:room cs)
                    :correlation_id correlation-id
                    :is_command true
                    :is_yetibot false
                    :user_id (str (:id user))
                    :user_name (:username user)}))

     (fact
      "it will NOT add the user message to history because user is yetibot,
       and return nil"
      (h/add-user-message-history nil {:yetibot? true} nil)
      => nil))))
