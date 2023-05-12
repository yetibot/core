(ns yetibot.core.test.adapters.slack
  (:require [yetibot.core.adapters.adapter :as a]
            [yetibot.core.adapters.slack :as slack]
            [yetibot.core.chat :as chat]
            [yetibot.core.handler :refer [handle-raw]]
            [clj-slack
             [channels :as channels]
             [conversations :as conversations]
             [users :as slack-users]
             [groups :as groups]
             [reactions :as reactions]
             [im :as im]
             [chat :as slack-chat]]
            [slack-rtm.core :as slack-rtm]
            [yetibot.core.models.users :as users]
            [midje.sweet :refer [=> fact facts contains provided
                                 anything every-checker throws]]))

(facts
 "about slack-config"
 (fact
  "gets default endpoint if none given"
  (slack/slack-config {}) => (contains {:api-url "https://slack.com/api"}))
 (fact
  "uses custom endpoint if given"
  (slack/slack-config {:endpoint "iamcustom"}) => (contains {:api-url "iamcustom"}))
 (fact
  "returns nil token if none given"
  (slack/slack-config {}) => (contains {:token nil}))
 (fact
  "returns custom token if given"
  (slack/slack-config {:token "iamcustom"}) => (contains {:token "iamcustom"})))

;; referencing slack data here :: https://api.slack.com/methods/channels.list
(facts
 "about channels-in"
 (fact
  "returns only channels YB is in"
  (slack/channels-in anything) => '({:is_member true :name "yes"})
  (provided (slack/list-channels anything) => {:channels [{:is_member true
                                                           :name "yes"}
                                                          {:is_member false
                                                           :name "no"}]})))

(facts
 "about chan-or-group-name"
 (fact
  "handles a channel"
  (slack/chan-or-group-name {:is_channel true :name "channel"})
  => "#channel")
 (fact
  "handles a group"
  (slack/chan-or-group-name {:is_channel false :name "group"})
  => "group"))

;; referencing slack data found here :: https://api.slack.com/methods/groups.list
(facts
 "about channels"
 (fact
  "merges the names of `channels-in` and `list-groups` into a
   non-empty collection"
  (slack/channels anything) => (every-checker coll?
                                              not-empty
                                              (contains '("group1" "#channel1")))
  (provided (slack/channels-in anything) => '({:is_member true :name "channel1"})
            (slack/list-groups anything) => {:groups [{:name "group1"}]})))

(facts
 "about unencode-message"
 (fact
  "unencodes a URL"
  (slack/unencode-message "<https://imgflip.com>") => "https://imgflip.com")
 (fact
  "unencodes a URL with text after it"
  (slack/unencode-message "<https://imgflip.com> .base-img[src!=''] src") =>
  "https://imgflip.com .base-img[src!=''] src")
 (fact
  "unencodes a URL with surrounding text"
  (slack/unencode-message
   "Why does slack surround URLs with cruft? Jerk. <https://imgflip.com> .base-img[src!=''] src")
  => "Why does slack surround URLs with cruft? Jerk. https://imgflip.com .base-img[src!=''] src")
 (fact
  "unencodes multiple URLs"
  (slack/unencode-message "Foo <https://imgflip.com> bar <https://www.google.com>") =>
  "Foo https://imgflip.com bar https://www.google.com")
 (fact
  "unencodes Slack's weird @channel and @here encodings"
  (slack/unencode-message "<!here> Slaaaaaaaaaaaaaaack") => "@here Slaaaaaaaaaaaaaaack"
  (slack/unencode-message "<!channel> also") => "@channel also"))

;; referencing slack data found here :: https://api.slack.com/methods/channels.info
(facts
 "about entity-with-name-by-id"
 (fact
  "assuming channel ID C123 has name of 'channel123', returns a [name entity]
   pair where channel has leading # attached"
  (slack/entity-with-name-by-id anything {:channel "C123"})
  => ["#channel123" {:name "channel123"}]
  (provided (slack/slack-config anything) => {:token "iamtoken"
                                              :api-url "https://slack.com/api"}
            (conversations/info anything anything) => {:channel {:name "channel123"}}))
 (fact
  "assuming direct message ID D123 has user name of 'user123', returns expected
   [name entity] pair with no mods"
  (slack/entity-with-name-by-id anything {:channel "D123"})
  => ["user123" {:name "user123"}]
  (provided (slack/slack-config anything) => anything
            (slack-users/info anything anything) => {:user {:name "user123"}}))
 (fact
  "assuming group message ID G123 has group name of 'group123', returns expected
   [name entity] pair with no mods"
  (slack/entity-with-name-by-id anything {:channel "G123"})
  => ["#group123" {:name "group123"}]
  (provided (slack/slack-config anything) => anything
            (conversations/info anything anything) => {:channel {:name "group123"}})))

(facts
 "about filter-chans-or-grps-containing-user"
 (fact
  "returns 'groups' a user is a member of, and only those groups"
  (slack/filter-chans-or-grps-containing-user
   "U123"
   [{:members ["U123"]}
    {:members ["U456"]}]) => (every-checker coll?
                                            not-empty
                                            (contains '({:members ["U123"]})))))

(facts
 "about send-msg"
 (fact
  "it will attempt to post a message to slack using modified params and log it"
  (let [msg "hello world"]
    (slack/send-msg :config msg) => :didlog
    (provided (slack-chat/post-message (slack/slack-config :config)
                                       anything
                                       msg
                                       (slack/->send-msg-options msg))
              => {:ok true}
              (slack/log-send-msg msg {:ok true}) => :didlog)))
 (fact
  "it will attempt to post an image to slack using modified params and log it"
  (let [img "https://a.a/a.jpg"]
    (slack/send-msg :config img) => :didlog
    (provided (slack-chat/post-message (slack/slack-config :config)
                                       anything
                                       img
                                       (slack/->send-msg-options img))
              => {:ok false}
              (slack/log-send-msg img {:ok false}) => :didlog)))
 (fact
  "it will exercise the code that checks for a truthy *thread-ts* binding,
   and not throw an error"
  (binding [yetibot.core.chat/*thread-ts* :ihaveathreadts]
    (let [msg "hello world"]
      (slack/send-msg :config msg) => :didlog
      (provided (slack-chat/post-message (slack/slack-config :config)
                                         anything
                                         msg
                                         (slack/->send-msg-options msg))
                => {:ok true}
                (slack/log-send-msg msg {:ok true}) => :didlog)))))

(facts
 "about find-yetibot-user"
 (fact
  "it will find the YB user when provided a connection and chat-source"
  (slack/find-yetibot-user :conn :cs) => true
  (provided (slack/self :conn) => {:id :myid}
            (users/get-user :cs :myid) => true)))

(facts
 "about channel-by-id"
 (fact
  "it will get the channel config map based on the id param"
  (slack/channel-by-id 123 :config) => {:id 123}
  (provided (slack/channels-cached :config) => [{:id 123}
                                                {:id 456}])))

(facts
 "about send-paste"
 (fact
  "it will attempt to post a paste message as an attachment to slack using
   modified params"
  (slack/send-paste :config "hello world") => :message-posted
  (provided (slack-chat/post-message (slack/slack-config :config)
                                     anything
                                     ""
                                     anything)
            => :message-posted))
 (fact
  "it will exercise the code that checks for a truthy *thread-ts* binding,
   and not throw an error"
  (binding [yetibot.core.chat/*thread-ts* :threadts]
    (slack/send-paste :config "hello world") => :message-posted
    (provided (slack-chat/post-message (slack/slack-config :config)
                                       anything
                                       ""
                                       anything)
              => :message-posted))))

(facts
 "about history"
 (fact
  "it will attempt to retrieve a group's history"
  (slack/history {:config :myadapter} "GROUPS") => :grouphistory
  (provided
   (slack/slack-config :myadapter) => :mycs
   (conversations/history :mycs "GROUPS") => :grouphistory)))

(facts
 "about start"
 (fact
  "it will stop and restart the provided adapter"
  (slack/start :myadapter nil nil nil) => :didstart
  (provided (slack/stop :myadapter) => :didstop
            (slack/restart :myadapter) => :didstart)))

(facts
 "about stop"
 (fact
  "it will reset whether it should ping and refernce the UUID implmentation
   and send a slack RTM event and reset the connection"
  (let [ping? (atom true)
        conn (atom {:dispatcher :myconn})
        adapter {:should-ping? ping? :conn conn}]
    (slack/stop adapter) => :didstop
    (provided (reset! ping? false) => :didreset
              (a/uuid adapter) => :diduuid
              (slack-rtm/send-event :myconn :close) => :didsendevent
              (reset! conn nil) => :didstop))))

(facts
 "about on-channel-left"
 (fact
  "it will get the chat source and remove users in related channel"
  (let [channel :mychannel
        cs {:uuid "abc-123" :room :myroom}]
    (slack/on-channel-left {:channel channel}) => nil
    (provided (chat/chat-source channel) => cs
              (users/get-users cs) => []
              (run! anything []) => nil))))

(facts
 "about on-channel-joined"
 (fact
  "it get the members of the joined channel and add the chat source to the
   user"
  (let [id :myid
        members [:mem1 :mem2]
        channel {:id id :members members}
        cs {:uuid "abc-123" :room :myroom}]
    (slack/on-channel-joined {:channel channel}) => nil
    (provided (chat/chat-source id) => cs
              (run! anything members) => nil))))

(facts
 "about handle-presence-change"
 (fact
  "it will get the presence and id of the user and update the user status
   with the associated adapter"
  (let [presence "active"
        user "U123"
        adapter {:adapter :slack}]
    (slack/handle-presence-change {:presence presence
                                   :user user}) => :doupdate
    (provided (chat/base-chat-source) => adapter
              (users/update-user adapter user {:active? true}) => :doupdate))))

(facts
 "about on-presence-change"
 (fact
  "it will handle a presence change event and hand it off to
   (handle-presence-change)"
  (slack/on-presence-change {}) => :handled
  (provided (slack/handle-presence-change {}) => :handled)))

(facts
 "about on-manual-presence-change"
 (fact
  "it will handle a manual presence change event and hand it off to
   (handle-presence-change)"
  (slack/on-manual-presence-change {}) => :handled
  (provided (slack/handle-presence-change {}) => :handled)))

(facts
 "about on-error"
 (fact
  "it will log an error - supa-easy, but sad we can't take the value
   of a macro"
  (slack/on-error :myexception) => nil))

(facts
 "about on-connect"
 (fact
  "it will reset the atoms associated with the adapter to true and start
   the pinger"
  (let [should? (atom false)
        connect? (atom false)
        adapter {:should-ping? should?
                 :connected? connect?}]
    (slack/on-connect adapter nil) => :doconnect
    (provided (reset! should? true) => true
              (reset! connect? true) => true
              (slack/start-pinger! adapter) => :doconnect))))

(facts
 "about stop-pinger!"
 (fact
  "it will reset the atom associated with the adapter to false"
  (let [should? (atom true)
        adapter {:should-ping? should?}]
    (slack/stop-pinger! adapter) => false
    (provided (reset! should? false) => false))))

(facts
 "about on-hello"
 (fact
  "it will log a debug about being connected to slack"
  (slack/on-hello :myevent) => nil))

(facts
 "about on-channel-join"
 (fact
  "it will destruct the event and use the event to get the entity, chat source
   related user, find the YB user, and pass as a raw 'enter' event using the
   derived values"
  (slack/on-channel-join {:channel "C123" :user "U123"} :myconn :myconfig)
  => :didjoin
  (provided (slack/entity-with-name-by-id :myconfig
                                          {:channel "C123"})
            => ["#C123" {:name "C123"}]
            (chat/chat-source "#C123") => :mycs
            (users/get-user :mycs "U123") => :myuser
            (slack/find-yetibot-user :myconn :mycs) => :ybuser
            (handle-raw :mycs :myuser :enter :ybuser {}) => :didjoin)))

(facts
 "about on-channel-leave"
 (fact
  "it will destruct the event and use the event to get the entity, chat source
   related user, find the YB user, and pass as a raw 'leave' event using the
   derived values"
  (slack/on-channel-leave {:channel "C123" :user "U123"} :myconn :myconfig)
  => :didleave
  (provided (slack/entity-with-name-by-id :myconfig
                                          {:channel "C123"})
            => ["#C123" {:name "C123"}]
            (chat/chat-source "#C123") => :mycs
            (users/get-user :mycs "U123") => :myuser
            (slack/find-yetibot-user :myconn :mycs) => :ybuser
            (handle-raw :mycs :myuser :leave :ybuser {}) => :didleave)))

(facts
 "about on-message-changed"
 (fact
  "it will destruct the event and use the event to get the entity, chat source
   related user, find the YB user, and pass as a raw 'message' event using the
   derived values, because this is not the YB user"
  (let [channel "C123"
        user "U123"
        text "my text"
        cs "#C123"]
    (slack/on-message-changed {:channel channel
                               :message {:user user
                                         :text text
                                         :thread_ts :mythread}}
                              :myconn
                              :myconfig)
    => :message-changed
    (provided (slack/entity-with-name-by-id :myconfig
                                            {:channel channel
                                             :user user})
              => [cs {:name channel}]
              (chat/chat-source cs) => :mycs
              (users/get-user :mycs user) => {}
              (slack/find-yetibot-user :myconn :mycs) => :ybuser
              (handle-raw :mycs
                          {:yetibot? false}
                          :message
                          :ybuser
                          {:body "my text"}) => :message-changed)))
 (fact
  "it will destruct the event and use the event to get the entity, chat source
   related user, find the YB user, and log out and return nil when YB user ID
   is equal to user ID"
  (let [channel "C123"
        user "U123"
        text "my text"
        cs "#C123"]
    (slack/on-message-changed {:channel channel
                               :message {:user user
                                         :text text
                                         :thread_ts :mythread}}
                              :myconn
                              :myconfig)
    => nil
    (provided (slack/entity-with-name-by-id :myconfig
                                            {:channel channel
                                             :user user})
              => [cs {:name channel}]
              (chat/chat-source cs) => :mycs
              (users/get-user :mycs user) => {}
              (slack/find-yetibot-user :myconn :mycs) => {:id user}))))

(facts
 "about on-pong"
 (fact
  "it .."
  (let [connection-last-active-timestamp (atom 123)
        connection-latency (atom 123)
        ping-time (atom 123)]
    (slack/on-pong {:conn :myconn
                    :event :myevent
                    :connection-last-active-timestamp connection-last-active-timestamp
                    :connection-latency connection-latency
                    :ping-time ping-time}
                   :pong-event) => :did-reset-latency
    (provided (reset! connection-last-active-timestamp anything) => :did-reset-last
              (reset! connection-latency anything) => :did-reset-latency))))

(facts
 "about react"
 (fact
  "It will react to the first message in a given channel histroy for an
   adapter that are non-YB commands and not issued by the YB user.
   There might be a bug here because what if the config'ed command alias
   is not of the ! variety?"
  (let [adapter {:config "slack" :conn :myconn}
        emoji ":smiley:"
        channel "#C123"
        {:keys [id] :as yb-id} {:id "U123"}]
    (slack/react adapter emoji channel) => :didreact
    (provided (slack/self :myconn) => yb-id
              (slack/history adapter channel) => {:messages
                                                  [{:user id
                                                    :text "skip cuz YB user"}
                                                   {:user "U456"
                                                    :text "!skip cuz command"}
                                                   {:user "U456"
                                                    :text "legit message"
                                                    :ts :somets}
                                                   {:user "U456"
                                                    :text "skip cuz 2nd"}]}
              (reactions/add anything emoji {:channel channel
                                             :timestamp :somets})
              => :didreact))))

(facts
 "about ->send-msg-options"
 (fact
  "it will detect a non-image and unfurl"
  (let [{:keys [unfurl_media]} (slack/->send-msg-options "helloworld")]
    unfurl_media => "true"))
 (fact
  "it will detect an image URL and not unfurl it with a block image type"
  (let [img "https://hello.com/world.jpg"
        {:keys [unfurl_media]
         [{:strs [type image_url alt_text]}] :blocks}
        (slack/->send-msg-options img)]
    unfurl_media => "false"
    alt_text => "Image"
    image_url => img
    type => "image"))
 (fact
  "it return message options that include thread_ts data when binding for
   *thread-ts* exists"
  (binding [yetibot.core.chat/*thread-ts* :threadts]
    (let [{:keys [unfurl_media thread_ts]} (slack/->send-msg-options
                                            "helloworld")]
      thread_ts => :threadts
      unfurl_media => "true"))))

(facts
 "about log-send-msg"
 (fact
  "it logs debug when everything is AOK, always returning nil"
  (slack/log-send-msg "https://a.a/a.jpg" {:ok true}) => nil)
 (fact
  "it logs error when everything is NOT AOK, always returning nil"
  (slack/log-send-msg "hello world" {:ok false}) => nil))
