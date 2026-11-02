(ns yetibot.core.test.adapters.discord-inspection
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [discljord.messaging :as messaging]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.chat :as chat]
            [yetibot.core.db.observe :as observers]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.users :as users]
            [yetibot.core.util.public-result :as public-result]))

(def result (atom nil))
(def executions (atom 0))
(defn fixture-cmd [_]
  (swap! executions inc)
  (if (:throw? @result)
    (throw (ex-info "PRIVATE-PROMPT" {:status 503 :body "PRIVATE-BODY"}))
    (if (:unreviewed? @result) @result (public-result/reviewed @result))))
(cmd-hook #"inspect-fixture" _ fixture-cmd)

(def source {:id "100" :channel-id "200"
             :author {:id "alice" :username "alice"}
             :content "!inspect-fixture"})
(defn- receipt [v] (deliver (promise) v))
(defn- await! [r]
  (when r
    (let [[_ port] (async/alts!! [r (async/timeout 5000)])]
      (when-not (= r port) (throw (Exception. "Inspection timeout"))))))
(defn- nodes [payload]
  (filter map? (tree-seq coll? seq payload)))
(defn- button [payload label]
  (some #(when (= label (:label %)) %) (nodes payload)))
(defn- text [payload]
  (str/join "\n" (keep #(when (= 10 (:type %)) (:content %))
                        (nodes payload))))
(defn- session [value check]
  (let [adapter (discord/make-discord {:name "inspect-test"})
        conn (:conn adapter) calls (atom []) ids (atom 0)]
    (reset! conn {:rest :rest})
    (reset! (:yetibot-user adapter) {:id "bot"})
    (with-redefs [result (atom value) executions (atom 0)
                  users/users (atom {})
                  channel/settings-for-chat-source (constantly {})
                  observers/find-all (constantly [])
                  handler/add-user-message-to-history (fn [& _])
                  handler/add-bot-response-to-history (fn [& _])
                  messaging/create-message!
                  (fn [_ target & options]
                    (swap! calls conj [:create target (apply hash-map options)])
                    (receipt {:id "answer" :channel-id target}))
                  messaging/edit-message!
                  (fn [_ target id & options]
                    (swap! calls conj [:edit target (apply hash-map options)])
                    (receipt {:id id :channel-id target}))
                  messaging/delete-message! (fn [& _] (receipt true))
                  messaging/create-interaction-response!
                  (fn [_ _ _ type & options]
                    (swap! calls conj
                           [:interaction type (:data (apply hash-map options))])
                    (receipt true))]
      (binding [chat/*adapter* adapter]
        (let [event! #(discord/handle-event %1 %2 conn (:yetibot-user adapter))
              click! (fn [data & [overrides]]
                       (event! :interaction-create
                         (merge {:id (str (swap! ids inc)) :token "interaction"
                                 :channel-id "200" :user {:id "bob"}
                                 :message {:id "answer" :author {:id "bot"}}
                                 :data data} overrides)))]
          (await! (event! :message-create source))
          (check calls conn event! click!))))))

(fact "reviewed public data drills down without retaining or rerunning raw data"
  (session {:result/value "Weather"
            :result/data {:private "PRIVATE-BODY" :prompt "PRIVATE-PROMPT"}
            :result/public-data {:location "Seattle"
                                 :weather {:temperature 21
                                           :days [{:high 25} {:high 26}]}
                                 :api_key "PRIVATE-CREDENTIAL"}}
    (fn [calls conn _ click!]
      (let [answer (last (last @calls))
            open (button answer "Explore data")]
        (some? open) => true
        (await! (click! {:custom-id (:custom_id open)}))
        (let [[_ type page] (last @calls)
              menu (some #(when (= 3 (:type %)) %) (nodes page))
              weather (some #(when (str/starts-with? (:label %) "weather") %)
                            (:options menu))]
          type => 4
          (str/includes? (text page) "Seattle") => true
          (:flags page) => 32768
          (:allowed_mentions page) => {:parse [] :replied_user false}
          (some? weather) => true
          (await! (click! {:custom-id (:custom_id menu)
                           :values [(:value weather)]}
                          {:message {:id "viewer" :author {:id "bot"}}}))
          (let [[_ type nested] (last @calls)]
            type => 7
            (str/includes? (text nested) "temperature") => true
            (some? (button nested "Back")) => true
            (await! (click! {:custom-id (:custom_id (button nested "Back"))}
                            {:message {:id "viewer" :author {:id "bot"}}}))))
        @executions => 1
        (str/includes? (pr-str (some-> (get-in @conn
                                         [:commands ["200" "100"] :inspection])
                                      deref)) "PRIVATE-") => false))))

(fact "error details expose only approved diagnostics and acknowledge missing bodies"
  (session {:result/error "Weather unavailable"
            :result/data {:body "PRIVATE-BODY" :headers {:authorization "SECRET"}}
            :result/public-error {:http-status 503 :stage "weather lookup"
                                  :headers {:authorization "PRIVATE-CREDENTIAL"}}}
    (fn [calls _ _ click!]
      (let [open (button (last (last @calls)) "Error details")]
        (some? open) => true
        (await! (click! {:custom-id (:custom_id open)}))
        (let [page (last (last @calls))]
          (str/includes? (text page) "503") => true
          (str/includes? (text page) "weather lookup") => true
          (str/includes? (text page) "not retained") => true
          (str/includes? (pr-str page) "PRIVATE-") => false))
      @executions => 1)))

(fact "opaque data and thrown exceptions never become automatic public snapshots"
  (session {:unreviewed? true :result/value "Result"
            :result/data {:secret "PRIVATE-CREDENTIAL"}
            :result/public-data {:forged "PRIVATE-CREDENTIAL"}
            :result/public-error {:forged "PRIVATE-PROMPT"}}
    (fn [calls _ _ click!]
      (button (last (last @calls)) "Explore data") => nil
      (await! (click! {:custom-id "yb:data:200:100"}))
      (str/includes? (text (last (last @calls))) "not retained") => true
      (str/includes? (pr-str @calls) "PRIVATE-") => false))
  (session {:throw? true}
    (fn [calls _ _ click!]
      (let [answer (last (last @calls))]
        (str/includes? (pr-str answer) "PRIVATE-") => false
        (await! (click! {:custom-id (:custom_id (button answer "Error details"))}))
        (str/includes? (text (last (last @calls))) "503") => true))))

(fact "viewer navigation expires on edit and rejects other channels or authors"
  (session {:result/value "Result" :result/public-data {:nested {:value 1}}}
    (fn [calls _ event! click!]
      (await! (click! {:custom-id (:custom_id
                                    (button (last (last @calls)) "Explore data"))}))
      (let [menu (some #(when (= 3 (:type %)) %) (nodes (last (last @calls))))
            data {:custom-id (:custom_id menu)
                  :values [(:value (first (:options menu)))]}]
        (await! (click! data {:channel-id "elsewhere"}))
        (str/includes? (text (last (last @calls))) "expired") => true
        (await! (click! data {:message {:id "viewer" :author {:id "attacker"}}}))
        (str/includes? (text (last (last @calls))) "expired") => true
        (await! (event! :message-update
                        (assoc source :content "!inspect-fixture edited")))
        (await! (click! data {:message {:id "viewer" :author {:id "bot"}}}))
        (str/includes? (text (last (last @calls))) "expired") => true
        @executions => 2))))
