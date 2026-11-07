(ns yetibot.core.test.adapters.discord-followups
  (:require [clojure.core.async :as async]
            [discljord.messaging :as messaging]
            [midje.sweet :refer [fact =>]]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.chat :as chat]
            [yetibot.core.handler :as handler]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.users :as users]
            [yetibot.core.util.public-result :as public]))

(def route {:command "ai" :provider "zen" :model "paid"
            :provider-signature (apply str (repeat 64 "a")) :no-tools true})
(defn host [name & args]
  (when-let [f (ns-resolve 'yetibot.core.adapters.discord name)]
    (apply f args)))
(defn event [id content & [reference]]
  (cond-> {:id id :channel-id "10" :content content
           :author {:id "7" :username "reader"}}
    reference (assoc :message-reference
                     {:message-id reference :channel-id "10"})))
(defn await! [result]
  (when result
    (let [[_ port] (async/alts!! [result (async/timeout 3000)])]
      (when-not (= result port)
        (throw (ex-info "Follow-up did not complete" {}))))))

(defn session [f]
  (let [adapter (discord/make-discord {:name "test"})
        conn (:conn adapter) seen (atom []) next-id (atom 199)
        sent (atom []) error? (atom false) fail? (atom false)]
    (reset! conn {:rest :fixture})
    (reset! (:yetibot-user adapter) {:id "9"})
    (with-redefs [users/users (atom {})
                  chat/chat-source
                  (fn [room] {:adapter :discord :uuid "test" :room room})
                  channel/settings-for-chat-source (constantly {})
                  handler/handle-raw
                  (fn [_ _ _ _ {:keys [body]}]
                    (let [followup (host 'command-followup)]
                      (swap! seen conj {:body body :route followup})
                      (async/thread
                        (when (or followup (.startsWith body "!ai "))
                          (host 'remember-command-continuation! route)
                          (when @error? (public/observe! {:result/error true}))
                          (chat/send-msg (if @error? "error" "answer"))))))
                  messaging/create-message!
                  (fn [_ target & options]
                    (when @fail? (throw (Exception. "unknown delivery")))
                    (let [options (apply hash-map options)
                          source (get-in options [:message-reference :message_id])
                          id (str (swap! next-id
                                     #(inc (max % (Long/parseLong source)))))]
                      (swap! sent conj [target id options])
                      (deliver (promise) {:id id :channel-id target})))
                  messaging/edit-message!
                  (fn [_ target id & _]
                    (deliver (promise) {:id id :channel-id target}))
                  messaging/delete-message!
                  (fn [& _] (deliver (promise) true))]
      (binding [chat/*adapter* adapter]
        (f {:conn conn :bot (:yetibot-user adapter) :seen seen :sent sent
            :error? error? :fail? fail?
            :send! #(await! (discord/handle-event :message-create % conn
                                                (:yetibot-user adapter)))})))))

(fact "successful owned answers admit literal replies with inherited routing"
  (session
    (fn [{:keys [send! seen sent]}]
      (send! (event "100" "!ai first"))
      (send! (event "300" "--model other | echo `danger`" "200"))
      (mapv :route @seen) => [nil route]
      (:body (second @seen)) => "--model other | echo `danger`"
      (count @sent) => 2
      (get-in (second @sent) [2 :message-reference :message_id]) => "300"
      (send! (event "400" "third turn" "301"))
      (:route (last @seen)) => route
      (count @sent) => 3)))

(fact "larger pinned budgets survive owned literal followups"
  (with-redefs [route (assoc route :max-tool-calls 128
                                  :max-output-tokens 262144)]
    (session
      (fn [{:keys [send! seen sent]}]
        (send! (event "100" "!ai first"))
        (send! (event "300" "--max-output-tokens 1 | echo literal" "200"))
        (:route (last @seen)) => route
        (count @sent) => 2))))

(fact "explicit commands override, duplicates do not re-run paid followups"
  (session
    (fn [{:keys [send! seen sent]}]
      (send! (event "100" "!ai first"))
      (send! (event "300" "followup" "200"))
      (send! (event "300" "followup" "200"))
      (count @sent) => 2
      (send! (event "400" "!echo explicit" "200"))
      (:route (last @seen)) => nil
      (count @sent) => 2)))

(fact "unknown, forwarded, cross-channel, future and bot replies never infer"
  (session
    (fn [{:keys [send! seen sent]}]
      (send! (event "100" "!ai first"))
      (doseq [message [(event "300" "ordinary chatter")
                        (event "301" "unknown" "199")
                        (event "150" "future" "200")
                        (assoc-in (event "302" "forwarded" "200")
                                  [:message-reference :type] 1)
                        (assoc-in (event "303" "cross-channel" "200")
                                  [:message-reference :channel-id] "11")
                        (assoc-in (event "304" "other room" "200")
                                  [:channel-id] "11")
                        (assoc-in (event "305" "bot" "200") [:author :bot] true)]]
        (send! message))
      (every? nil? (map :route @seen)) => true
      (count @sent) => 1)))

(fact "errors and unconfirmed delivery never become continuation parents"
  (doseq [failure [:error? :fail?]]
    (session
      (fn [{:keys [send! seen] :as fixture}]
        (reset! (get fixture failure) true)
        (send! (event "100" "!ai first"))
        (reset! (get fixture failure) false)
        (send! (event "300" "do not retry" "200"))
        (:route (last @seen)) => nil))))

(fact "revisions and message deletion immediately invalidate parent ownership"
  (doseq [change [:edit :delete :bulk-delete :evict]]
    (session
      (fn [{:keys [send! seen conn bot]}]
        (send! (event "100" "!ai first"))
        (try
          (case change
            :edit (await! (discord/handle-event :message-update
                            (event "100" "!echo no longer AI") conn bot))
            :delete (discord/handle-event :message-delete
                      {:id "200" :channel-id "10"} conn bot)
            :bulk-delete (discord/handle-event :message-delete-bulk
                           {:ids ["100"] :channel-id "10"} conn bot)
            :evict (do (swap! conn dissoc :commands) nil))
          (catch IllegalArgumentException _ :missing)) => nil
        (send! (event "300" "stale" "200"))
        (:route (last @seen)) => nil))))

(fact "continuation registration requires an owned active command"
  (host 'remember-command-continuation! route) => :unavailable
  (host 'command-followup) => nil)

(fact "continuation metadata is closed, bounded and never executable text"
  (doseq [bad [(assoc route :command "echo")
               (assoc route :model "paid | echo leak")
               (assoc route :prompt "private prompt")
               (assoc route :headers {"Authorization" "private"})
               (assoc route :callback identity)
               (assoc route :toolset (apply str (repeat 101 "x")))
               (assoc route :max-tool-calls 129)
               (assoc route :max-output-tokens 262145)
               (assoc route :max-output-tokens 0)
               (assoc route :max-output-tokens "65536")
               (assoc route :verified-free "true")]]
    (try (host 'remember-command-continuation! bad) :unsafe
         (catch IllegalArgumentException _ :blocked)) => :blocked))
