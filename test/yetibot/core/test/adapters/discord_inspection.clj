(ns yetibot.core.test.adapters.discord-inspection
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [discljord.messaging :as messaging]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.adapters.discord-inspection :as inspection]
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

(fact "reviewed public text pages keep files/footer and never execute again"
  (let [full (str (apply str (repeat 2200 "Full 😀 description. ")) "END")]
    (session {:result/value full :result/public-text full
              :result/data {:private "PRIVATE-BODY"}}
      (fn [calls conn _ click!]
        (let [answer (last (last @calls))
              state (get-in @conn [:commands ["200" "100"] :inspection])
              pages (get-in @state [:text-pages :pages])]
          (apply str pages) => full
          (count pages) => #(> % 2)
          (some? (button answer "Next")) => true
          (slurp (get-in answer [:stream :content])) => full
          (:disabled (button answer "Previous")) => true
          (str/includes? (text answer) "Page 1 of") => true
          (loop [payload answer n 1]
            (when (< n (count pages))
              (await! (click! {:custom-id (:custom_id (button payload "Next"))}))
              (let [[_ type payload] (last @calls)]
                type => 7
                (str/includes? (text payload) (str "Page " (inc n) " of")) => true
                (<= (count (text payload)) 4000) => true
                (count (re-seq #"-# [0-9]+[.][0-9]s" (text payload))) => 1
                (filter #(= 13 (:type %)) (nodes payload))
                => (filter #(= 13 (:type %)) (nodes answer))
                (:allowed_mentions payload) => {:parse [] :replied_user false}
                (recur payload (inc n)))))
          (:disabled (button (last (last @calls)) "Next")) => true
          (str/includes? (text (last (last @calls))) "END") => true
          ;; The same frozen page can be revisited safely, not a command replay.
          (await! (click! {:custom-id (:custom_id (button answer "Next"))}))
          @executions => 1
          (str/includes? (pr-str @state) "PRIVATE-BODY") => false
          (doseq [overrides [{:channel-id "elsewhere"}
                             {:message {:id "foreign" :author {:id "bot"}}}
                             {:message {:id "answer" :author {:id "other"}}}]]
            (await! (click! {:custom-id (:custom_id (button answer "Next"))}
                           overrides))
            (second (last @calls)) => 4)
          ;; Actual bot reply, but forged snapshot token or page.
          (doseq [id [(str/replace (:custom_id (button answer "Next"))
                                  (:token @state) "0000000000000000")
                       (str/replace (:custom_id (button answer "Next"))
                                    #":[0-9]+$" ":999")]]
            (await! (click! {:custom-id id}))
            (second (last @calls)) => 4)
          (reset! (:revision (get-in @conn [:commands ["200" "100"]])) (Object.))
          (swap! conn update :commands dissoc ["200" "100"])
          (await! (click! {:custom-id (:custom_id (button answer "Next"))}))
          (second (last @calls)) => 4
          @executions => 1)))))

(fact "unreviewed text and transformed pipeline output cannot gain paging"
  (let [full (apply str (repeat 5000 "x"))]
    (doseq [value [{:result/value full :result/public-text full :unreviewed? true}
                  {:result/value "Filtered answer" :result/public-text full}]]
      (session value
        (fn [calls _ _ _]
          (button (last (last @calls)) "Next") => nil)))))

(fact "data opens inline at two levels and changes whole-tree depth without reruns"
  (session {:result/value "Weather"
            :result/data {:private "PRIVATE-BODY" :prompt "PRIVATE-PROMPT"}
            :result/public-data {:location "Seattle"
                                 :weather {:temperature 21
                                           :condition {:description "Light rain"}}
                                 :units {:temperature "C" :wind "km/h"}
                                 :api_key "PRIVATE-CREDENTIAL"}}
    (fn [calls conn _ click!]
      (let [answer (last (last @calls))]
        (await! (click! {:custom-id (:custom_id (button answer "Explore data"))}))
        (let [[_ type page] (last @calls)]
          type => 7
          (str/includes? (text page) "Weather") => true
          (str/includes? (text page) "Seattle") => true
          (str/includes? (text page) "temperature") => true
          (get-in (json/read-str (second (re-find #"(?s)```json\n(.*?)\n```"
                                                  (text page))))
                  ["units" "wind"]) => "km/h"
          (str/includes? (text page) "Light rain") => false
          (str/includes? (text page) "Depth 2") => true
          (:flags page) => 32768
          (:allowed_mentions page) => {:parse [] :replied_user false}
          (some #(when (= 3 (:type %)) %) (nodes page)) => nil
          (some? (button page "Expand")) => true
          (some? (button page "Collapse")) => true
          (some? (button page "Hide data")) => true
          (await! (click! {:custom-id (:custom_id (button page "Expand"))}))
          (let [expanded (last (last @calls))]
            (str/includes? (text expanded) "Light rain") => true
            (str/includes? (text expanded) "Depth 3") => true
            (:disabled (button expanded "Expand")) => true
            (await! (click! {:custom-id (:custom_id (button expanded "Collapse"))})))
          (str/includes? (text (last (last @calls))) "Light rain") => false
          (await! (click! {:custom-id (:custom_id
                                      (button (last (last @calls)) "Hide data"))})))
        (:components (last (last @calls))) => (:components answer)
        ;; Reopening resets to the two-level overview.
        (await! (click! {:custom-id (:custom_id (button answer "Explore data"))}))
        (str/includes? (text (last (last @calls))) "Depth 2") => true
        (count (filter #(= :create (first %)) @calls)) => 1
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
      (str/includes? (text (last (last @calls))) "expired") => true
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
      (let [data {:custom-id (:custom_id (button (last (last @calls)) "Collapse"))}]
        (await! (click! data {:channel-id "elsewhere"}))
        (str/includes? (text (last (last @calls))) "expired") => true
        (await! (click! data {:message {:id "viewer" :author {:id "attacker"}}}))
        (str/includes? (text (last (last @calls))) "expired") => true
        (await! (event! :message-update
                        (assoc source :content "!inspect-fixture edited")))
        (await! (click! data {:message {:id "viewer" :author {:id "bot"}}}))
        (str/includes? (text (last (last @calls))) "expired") => true
        @executions => 2))))

(fact "error details expand and collapse the original card without a new message"
  (session {:result/error "Weather unavailable"
            :result/public-error {:http-status 503 :stage "weather lookup"}}
    (fn [calls _ _ click!]
      (let [original (last (last @calls))]
        (await! (click! {:custom-id (:custom_id
                                     (button original "Error details"))}))
        (let [[_ type expanded] (last @calls)]
          type => 7
          (str/includes? (text expanded) "Weather unavailable") => true
          (str/includes? (text expanded) "503") => true
          (some? (button expanded "Hide error details")) => true
          (await! (click! {:custom-id (:custom_id
                                       (button expanded "Hide error details"))})))
        (second (last @calls)) => 7
        (:components (last (last @calls))) => (:components original)
        (count (filter #(= :create (first %)) @calls)) => 1
        @executions => 1))))

(fact "error trees share data depth controls and reject stale or foreign cards"
  (session {:result/error "One model failed"
            :result/public-error
            {:reason "partial-result"
             :calls [{:model "example-model" :outcome "error"
                      :diagnostic {:code "request-rejected" :http_status 400}}]}}
    (fn [calls conn event! click!]
      (await! (click! {:custom-id (:custom_id
                                    (button (last (last @calls)) "Error details"))}))
      (let [initial (last (last @calls))
            expand (button initial "Expand")]
        (str/includes? (text initial) "Depth 2") => true
        (some #(when (= 3 (:type %)) %) (nodes initial)) => nil
        (some? expand) => true
        (when expand
          (let [data {:custom-id (:custom_id expand)}
                token (:token @(get-in @conn
                                  [:commands ["200" "100"] :inspection]))]
            (await! (click! data))
            (second (last @calls)) => 7
            (str/includes? (text (last (last @calls))) "Depth 3") => true
            (await! (click! {:custom-id (:custom_id
                                  (button (last (last @calls)) "Expand"))}))
            (str/includes? (text (last (last @calls))) "request-rejected") => true
            (str/includes? (text (last (last @calls))) "One model failed") => true
            (await! (click! {:custom-id (:custom_id
                                  (button (last (last @calls)) "Collapse"))}))
            (str/includes? (text (last (last @calls))) "Depth 3") => true
            (doseq [overrides [{:channel-id "another-channel"}
                               {:message {:id "another-bot-message"
                                          :author {:id "bot"}}}
                               {:message {:id "answer"
                                          :author {:id "other"}}}]]
              (await! (click! data overrides))
              (second (last @calls)) => 4)
            (doseq [id [(str/replace (:custom-id data) token "0000000000000000")
                         (str/replace (:custom-id data) #":[0-9]+$" ":99")
                         (str "yb:error-tree:200:100:" token ":e:8:0")]]
              (await! (click! {:custom-id id}))
              (second (last @calls)) => 4)
            (await! (event! :message-update
                            (assoc source :content "!inspect-fixture edited")))
            (await! (click! data))
            (second (last @calls)) => 4
            (str/includes? (text (last (last @calls))) "expired") => true
            @executions => 2))))))

(fact "overflow replies do not offer unrestorable inline controls"
  (let [state (atom (assoc (inspection/initial) :sealed? true
                          :error? true :data? true))
        payload (#'discord/reply-options
                  {:content "Overflow notice" :command-key ["200" "100"]
                   :inspection state :inspection-restorable? false})]
    (button payload "Error details") => nil
    (button payload "Explore data") => nil
    (some? (button payload "Run again")) => true))

(fact "data and error ownership is rechecked after rendering before inline IO"
  (doseq [data? [false true]]
    (session (if data?
               {:result/value "Weather" :result/public-data {:temperature 21}}
               {:result/error "Weather unavailable"
                :result/public-error {:http-status 503}})
      (fn [calls conn _ click!]
        (let [renderer (if data? #'inspection/inline-data-payload
                                #'inspection/inline-error-payload)
              render @renderer
              control {:custom-id (:custom_id
                                    (button (last (last @calls))
                                            (if data? "Explore data" "Error details")))}]
          (with-redefs-fn
            {renderer (fn [& args]
                        (let [data (apply render args)]
                          (reset! (get-in @conn
                                    [:commands ["200" "100"] :revision]) (Object.))
                          data))}
            #(await! (click! control)))
          (second (last @calls)) => 4
          (some #(when (= [:interaction 7] (take 2 %)) %) @calls) => nil
          @executions => 1)))))

(fact "unknown data/error acknowledgements are bounded and duplicates never retry"
  (doseq [data? [false true]]
    (session (if data?
               {:result/value "Weather" :result/public-data {:temperature 21}}
               {:result/error "Weather unavailable"
                :result/public-error {:http-status 503}})
      (fn [calls _ _ click!]
        (let [attempts (atom 0)
              control {:custom-id (:custom_id
                                    (button (last (last @calls))
                                            (if data? "Explore data" "Error details")))}]
          (with-redefs [messaging/create-interaction-response!
                        (fn [& _] (swap! attempts inc) (promise))]
            (await! (click! control {:id "one-update"}))
            (await! (click! control {:id "one-update"})))
          @attempts => 1
          (count (filter #(= :create (first %)) @calls)) => 1
          (count (filter #(= :interaction (first %)) @calls)) => 0
          @executions => 1)))))

(fact "data/error expansion fits text limits and restores the exact long answer"
  (doseq [data? [false true]
          error [(str "Long error\n\n```text\n"
                     (apply str (repeat 3800 "x")) "\n```")
                (str "\n" (apply str (repeat 900 "`")) "\n"
                     (apply str (repeat 1500 "x")) "\n"
                     (apply str (repeat 900 "`")))
                (apply str (repeat 1800 "🙂"))]]
  (session (if data?
             {:result/value error :result/public-data {:temperature 21}}
             {:result/error error
              :result/public-error {:http-status 503 :stage "weather lookup"}})
    (fn [calls _ _ click!]
      (let [original (last (last @calls))]
        (await! (click! {:custom-id (:custom_id
                                     (button original (if data? "Explore data"
                                                               "Error details")))}))
        (let [expanded (last (last @calls))]
          (reduce + (keep #(when (= 10 (:type %)) (count (:content %)))
                          (nodes expanded))) => #(<= % 4000)
          (count (re-seq #"(?m)^-# [0-9]+[.][0-9]s$" (text expanded))) => 1
          (contains? expanded :stream) => false
          (contains? expanded :attachments) => false
          (await! (click! {:custom-id (:custom_id
                                       (button expanded (if data? "Hide data"
                                                                  "Hide error details")))})))
        (:components (last (last @calls))) => (:components original)
        @executions => 1)))))

(fact "data and diagnostics replace one another inline using one original base"
  (session {:result/error "Weather unavailable"
            :result/public-error {:http-status 503 :stage "weather lookup"}
            :result/public-data {:temperature 21}}
    (fn [calls _ _ click!]
      (let [original (last (last @calls))]
        (await! (click! {:custom-id (:custom_id (button original "Explore data"))}))
        (let [data (last (last @calls))]
          (some? (button data "Hide data")) => true
          (await! (click! {:custom-id (:custom_id (button data "Error details"))})))
        (let [error (last (last @calls))]
          (some? (button error "Hide error details")) => true
          (button error "Hide data") => nil
          (str/includes? (text error) "**Explore data**") => false
          (await! (click! {:custom-id (:custom_id (button error "Explore data"))})))
        (let [data (last (last @calls))]
          (str/includes? (text data) "**Error details**") => false
          (some? (button data "Error details")) => true
          (await! (click! {:custom-id (:custom_id (button data "Hide data"))})))
        (:components (last (last @calls))) => (:components original)
        (count (filter #(= :create (first %)) @calls)) => 1
        @executions => 1))))

(fact "data controls reject forged tokens, other bot replies and legacy viewers"
  (session {:result/value "Answer" :result/public-data {:nested {:value 21}}}
    (fn [calls conn _ click!]
      (let [original (last (last @calls))
            token (:token @(get-in @conn [:commands ["200" "100"] :inspection]))
            control {:custom-id (:custom_id (button original "Explore data"))}]
        (await! (click! control {:message {:id "unrelated" :author {:id "bot"}}}))
        (second (last @calls)) => 4
        (await! (click! {:custom-id (str/replace (:custom-id control) token
                                                 "0000000000000000")}))
        (second (last @calls)) => 4
        (await! (click! {:custom-id (str "yb:view:200:100:" token ":d:0:0")}))
        (second (last @calls)) => 4
        (swap! conn update :commands dissoc ["200" "100"])
        (await! (click! control))
        (str/includes? (text (last (last @calls))) "expired") => true
        (count (filter #(= :create (first %)) @calls)) => 1
        @executions => 1))))
