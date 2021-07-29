(ns yetibot.core.test.handler
  (:require
   [midje.sweet :refer [facts fact => =not=> contains provided]]
   [yetibot.core.handler :as h]
   [clojure.string :as s]
   [yetibot.core.interpreter :as i]
   [yetibot.core.commands.echo]
   [yetibot.core.chat :refer [chat-data-structure]]))

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
 "about add-user-message-to-history"
 (let [body "!echo hello"
       user {:id 123 :username "greg" :yetibot? false}
       cmd-param-map (h/->cmd-param-map body
                                        user
                                        nil
                                        nil)
       yb-cmd-param-map (h/->cmd-param-map body
                                           (assoc user
                                                  :yetibot?
                                                  true)
                                           nil
                                           nil)
       cs {:adapter :slack :uuid :test :room "#C123"}]
   (binding [i/*chat-source* cs]
     (fact
      "it will add to the history table a user message, with related message
       metadata"
      (first (h/add-user-message-to-history cmd-param-map))
      => (contains {:body body
                    :chat_source_room (:room cs)
                    :correlation_id (:correlation-id cmd-param-map)
                    :is_command true
                    :is_yetibot false
                    :user_id (str (:id user))
                    :user_name (:username user)}))
     
     (fact
      "it will NOT add the user message to history because user is yetibot,
       and return nil"
      (h/add-user-message-to-history yb-cmd-param-map)
      => nil))))

(facts
 "about handle-parsed-expr"
 (let [user {:id 123 :username "greg" :yetibot? false}
       yb-user {:id 456 :username "yetibot" :yetibot? true}
       cmd-param-map {:user user :yetibot-user yb-user}]
   (fact
    "it will return nil when no legit parse tree is passed in as an arg"
    (h/handle-parsed-expr cmd-param-map nil) => nil)

   (fact
    "it will return a map with the value of the response, related data,
     and settings"
    (h/handle-parsed-expr cmd-param-map
                          (first
                           (:parsed-cmds (h/->parsed-message-info
                                          "!echo hello"))))
    => (contains {:data nil?
                  :settings not-empty
                  :value "hello"}))))

(facts
 "about ->handled-expr-info"
 (let [user {:id 123 :username "greg" :yetibot? false}
       yb-user {:id 456 :username "yetibot" :yetibot? true}
       cmd-param-map {:user user :yetibot-user yb-user}
       pe (first
           (:parsed-cmds (h/->parsed-message-info
                          "!echo hello")))
       hpe (h/handle-parsed-expr cmd-param-map
                                 pe)]
   (fact
    "it transforms a successfully handled parsed expression (command) and
     returns an info map of related expression info that signifies there
     was no error, the formatted response, as well as the original command"
    (h/->handled-expr-info hpe pe)
    => (contains {:error? false
                  :formatted-response "hello"
                  :original-command-str "echo hello"
                  :result "hello"}))))

(facts
 "about add-bot-response-to-history"
 (let [body "!echo hello"
       user {:id 123 :username "greg" :yetibot? false}
       yb-user {:id 456 :username "yetibot" :yetibot? true}
       cmd-param-map (h/->cmd-param-map body user yb-user true)
       cs {:adapter :slack :uuid :test :room "#C123"}
       pe (first
           (:parsed-cmds cmd-param-map))
       hpe (h/handle-parsed-expr cmd-param-map pe)]
   (binding [i/*chat-source* cs]
     (fact
      "it will take the parsed YB bot response from a command and add
       it to the history database with the defined correlation id"
      (first (h/add-bot-response-to-history (h/->handled-expr-info hpe pe)
                                            cmd-param-map))
      => (contains {:body "hello"
                    :chat_source_room (:room cs)
                    :correlation_id (:correlation-id cmd-param-map)
                    :is_command false
                    :is_yetibot true
                    :is_error false
                    :user_id (str (:id yb-user))
                    :user_name (:username yb-user)}))
     
     (fact
      "it will NOT add the bot response to history because the
       `record-yetibot-response?` option is not true"
      (first (h/add-bot-response-to-history (h/->handled-expr-info hpe pe)
                                            (assoc cmd-param-map
                                                   :record-yetibot-response?
                                                   false)))
      => nil))))

(facts
 "about ->handled-expr-results"
 (fact
  "it will return a collection that signifies a timeout was reached during
   the processing of a message, when the results arg is not truthy"
  (:timeout? (first (h/->handled-expr-results nil nil))) => true)
 
 (fact
  "it will return the results when the results arg evaluates to something
   truthy"
  (h/->handled-expr-results nil true) => true))

(facts
 "about dispatch-command-response"
 (fact
  "it will take a valid and successful result and pass it to be formatted
   and send to chat adapter"
  (h/dispatch-command-response {:embedded? false
                                :error? false
                                :result :myresult}) => :didsend
  (provided (chat-data-structure :myresult) => :didsend))
 
 (fact
  "it will take a non-successful command response, log it, and return nil"
  (h/dispatch-command-response {:embedded? true
                                :error? true
                                :result :myresult}) => nil))