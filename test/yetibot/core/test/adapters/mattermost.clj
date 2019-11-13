(ns yetibot.core.test.adapters.mattermost
  "This can be used to start/stop a Mattermost adapter for the purpose of manual
   testing during development."
  (:require
   [gniazdo.core :as ws]
   [yetibot.core.repl :refer [load-minimal-with-db]]
   [yetibot.core.logging :as logging]
   [midje.sweet :refer [=> fact facts]]
   [yetibot.core.adapters :as adapters]
   [yetibot.core.adapters.adapter :as a]
   [yetibot.core.adapters.mattermost :refer :all]
   [mattermost-clj.core :as mattermost]
   [mattermost-clj.api.teams :as teams]
   [mattermost-clj.api.posts :as posts]
   [mattermost-clj.api.channels :as channels]
   [yetibot.core.chat :as chat]))

(defn mattermost-config []
  (->>
   (adapters/adapters-config)
   (filter (fn [[uuid c]] (= "mattermost" (:type c))))
   (map (fn [[uuid c]] (assoc c :name uuid)))))

(comment

  ;; emoji reaction on a threaded child post

  {:event "reaction_added"
   :data {:reaction "{\"user_id\":\"rcc53m4fib8rir97r599uqh77o\"
                     \"post_id\":\"pxjk7ztxxt8z3er46n1ebmo4yw\"
                     \"emoji_name\":\"sunglasses\"
                     \"create_at\":1582572624251}"}
   :broadcast {:omit_users nil
               :user_id ""
               :channel_id "tn6qzzxpu3grjb6phdrjqi8z1r"
               :team_id ""}
   :seq 8}

  ;; emoji reaction on a top level post

  {:event "reaction_added"
   :data {:reaction "{\"user_id\":\"rcc53m4fib8rir97r599uqh77o\"
                     \"post_id\":\"hubusrp73pf1pyt6tccn8twzua\"
                     \"emoji_name\":\"cowboy_hat_face\"
                     \"create_at\":1582572393595}"}
   :broadcast {:omit_users nil
               :user_id ""
               :channel_id "tn6qzzxpu3grjb6phdrjqi8z1r"
               :team_id ""}
   :seq 2}

  {:event "reaction_added"
   :data {:reaction "{\"user_id\":\"rcc53m4fib8rir97r599uqh77o\"
                     \"post_id\":\"bajd57gmxf8opg79ic6sx6np4e\"
                     \"emoji_name\":\"sunglasses\"
                     \"create_at\":1580932374242}"}
   :broadcast {:omit_users nil
               :user_id ""
               :channel_id "fi5ub1195fyxdx686osuyu3jjo"
               :team_id ""}
   :seq 7}

  ;; example posted event from a private channel


  {:root_id ""
   :update_at 1579899683736
   :pending_post_id "rcc53m4fib8rir97r599uqh77o:1579899683585"
   :type ""
   :is_pinned false
   :channel_id "o63qmauf6py3mq37ijzu48ow9y"
   :parent_id ""
   :id "md7bftrjk786fyeza9tjrur6tr"
   :hashtags ""
   :delete_at 0
   :user_id "rcc53m4fib8rir97r599uqh77o"
   :edit_at 0
   :original_id ""
   :metadata {}
   :create_at 1579899683736
   :message "hi private channel"
   :props {}}

  ;; example posted event from a public channel
  {:root_id ""
   :update_at 1579899778765
   :pending_post_id "rcc53m4fib8rir97r599uqh77o:1579899778538"
   :type ""
   :is_pinned false
   :channel_id "mcq36tjo3fd9xdedd71b35x7ro"
   :parent_id ""
   :id "jfmyzgrxp38e8exqsbdq5j7zry"
   :hashtags ""
   :delete_at 0
   :user_id "rcc53m4fib8rir97r599uqh77o"
   :edit_at 0
   :original_id ""
   :metadata {}
   :create_at 1579899778765
   :message "back to public"
   :props {}}

  ;; example bot post
  {:root_id ""
   :update_at 1580322830846
   :pending_post_id ""
   :type ""
   :is_pinned false
   :channel_id "o63qmauf6py3mq37ijzu48ow9y"
   :parent_id ""
   :id "gag4ak7h1ffb787dobz4c7paxo"
   :hashtags ""
   :delete_at 0
   :user_id "i6qn89tmmbgydbb1sjjezbhm9c"
   :edit_at 0
   :original_id ""
   :metadata {}
   :create_at 1580322830846
   :message "hi"
   :props {:from_bot "true"}}

  ;; example user
  {:email "yetibot@localhost",
   :first_name "Yetibot",
   :timezone
   {:automaticTimezone "",
    :manualTimezone "",
    :useAutomaticTimezone "true"},
   :is_bot true,
   :locale "en",
   :last_picture_update 1573415720650,
   :last_password_update 1573415719758,
   :update_at 1573672594975,
   :roles "system_user system_admin",
   :nickname "",
   :auth_service "",
   :username "yetibot",
   :auth_data "",
   :id "i6qn89tmmbgydbb1sjjezbhm9c",
   :delete_at 0,
   :last_name "",
   :position "",
   :bot_description "yetibot.com",
   :create_at 1573415719758,
   :notify_props
   {:email "true",
    :first_name "false",
    :push "mention",
    :push_status "away",
    :comments "never",
    :channel "true",
    :mention_keys "yetibot,@yetibot",
    :desktop "mention",
    :desktop_sound "true"}}

  (load-minimal-with-db)
  ;; it can be useful to look at the logs in tail in a separate terminal:
  (logging/start)

  (def adapter
    (-> (mattermost-config)
        first
        (make-mattermost)))

  (a/start adapter)

  (a/stop adapter)

  ;; play with mattermost REST API

  (mattermost/with-api-context
    (:api-context adapter)
    (teams/teams-get))

  ;; child post
  (mattermost/with-api-context
    (:api-context adapter)
    (posts/posts-post-id-get "dqert4uboifwmc4747m4gif61r"))

  ;; parent post
  (mattermost/with-api-context
    (:api-context adapter)
    (posts/posts-post-id-get "c6pb93xuy7nf5eg5icfyp3ck7r"))




  ;; play with the adapter

  ;; start the first mattermost adapter for dev
  (mattermost-config)

  (stop-pinger! adapter)

  (type adapter)

  (a/platform-name adapter)

  (a/uuid adapter)

  (require '[clojure.reflect :as r])

  (:members (r/reflect @(:conn adapter)))
  @(:conn adapter)

  (ws/close @(:conn adapter))

  (binding [*config* (last (mattermost-config))]
    (channels))

  (binding [*config* (last (mattermost-config))]
    (:groups (list-groups))))

