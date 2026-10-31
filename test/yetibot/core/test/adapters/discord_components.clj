(ns yetibot.core.test.adapters.discord-components
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [discljord.messaging :as messaging]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.adapters.discord-components :as components]
            [yetibot.core.chat :as chat]
            [yetibot.core.handler :as handler]
            [yetibot.core.models.help :as help]
            [yetibot.core.webapp.routes.images :as images]
            [midje.sweet :refer [fact facts =>]]))

(def original {:id "command" :channel-id "channel" :content "!meme random"
               :author {:id "alice" :username "alice"}})

(defn- receipt [value] (deliver (promise) value))
(defn- await! [channel]
  (when channel
    (let [[_ port] (async/alts!! [channel (async/timeout 3000)])]
      (when-not (= port channel) (throw (Exception. "Command timeout"))))))

(defn- session [evaluate check]
  (let [adapter (discord/make-discord {:name "v2-test"})
        conn (:conn adapter)
        calls (atom [])
        ids (atom 0)]
    (reset! conn {:rest :rest :status-rest :status-rest})
    (with-redefs [chat/chat-source (fn [room] {:adapter :discord :room room})
                  handler/handle-raw
                  (fn [source user _ _ info]
                    (async/thread (evaluate (:body info) user source)))
                  messaging/create-message!
                  (fn [_ channel & options]
                    (let [id (str (swap! ids inc))]
                      (swap! calls conj [:create channel id (apply hash-map options)])
                      (receipt {:id id :channel-id channel})))
                  messaging/edit-message!
                  (fn [_ channel id & options]
                    (swap! calls conj [:edit channel id (apply hash-map options)])
                    (receipt {:id id :channel-id channel}))
                  messaging/delete-message!
                  (fn [_ channel id]
                    (swap! calls conj [:delete channel id]) (receipt true))
                  messaging/delete-original-interaction-response!
                  (fn [_ application _]
                    (swap! calls conj [:interaction-delete application])
                    (receipt true))
                  messaging/create-interaction-response!
                  (fn [_ id _ type & options]
                    (swap! calls conj [:interaction id type (apply hash-map options)])
                    (receipt true))]
      (binding [chat/*adapter* adapter]
        (check (fn [kind event]
                 (discord/handle-event kind event conn (atom {:id "bot"})))
               calls conn)))))

(defn- children [payload] (get-in payload [:components 0 :components]))
(defn- button [payload label]
  (first (filter #(= label (:label %))
                 (mapcat :components (filter #(= 1 (:type %)) (children payload))))))
(defn- click [id custom-id user]
  {:id id :token "never-log-me" :type 3 :channel-id "channel"
   :user {:id user :username user}
   :message {:id "1" :author {:id "bot"}}
   :data {:component-type 2 :custom-id custom-id}})

(fact "ordinary replies are V2 cards with actionable command controls"
  (session (fn [& _] (chat/send-msg "hello"))
    (fn [event! calls _]
      (await! (event! :message-create original))
      (let [payload (last (first @calls))]
        (:flags payload) => 32768
        (:content payload) => nil
        (get-in payload [:components 0 :type]) => 17
        (get-in payload [:components 0 :accent_color]) => 0x08B8CD
        (first (children payload)) => {:type 10 :content "hello"}
        (boolean (:custom_id (button payload "Run again"))) => true
        (boolean (:custom_id (button payload "Help"))) => true))))

(fact "generated uploads are explicitly exposed as media and retain the caption"
  (with-redefs [images/image-store (atom {"photo" {:data "SGVsbG8="}})]
    (session (fn [& _] (chat/send-msg "Caption https://example.org/generated-images/photo.png"))
      (fn [event! calls _]
        (await! (event! :message-create original))
        (let [payload (last (first @calls))]
          (:flags payload) => 32768
          (first (children payload)) => {:type 10 :content "Caption "}
          (get-in (second (children payload)) [:items 0 :media :url])
          => "attachment://photo.png"
          (get-in payload [:stream :filename]) => "photo.png")))))

(fact "generated image captions use the same deterministic table renderer"
  (with-redefs [images/image-store (atom {"photo" {:data "SGVsbG8="}})]
    (session (fn [& _]
               (chat/send-msg (str "|Key|Value|\n|---|---|\n|a|b|\n"
                                   "https://example.org/generated-images/photo.png")))
      (fn [event! calls _]
        (await! (event! :message-create original))
        (let [payload (last (first @calls))]
          (count @calls) => 1
          (first (children payload))
          => {:type 10 :content "```text\nKey  Value\na    b\n```\n"}
          (get-in (second (children payload)) [:items 0 :media :url])
          => "attachment://photo.png"
          (slurp (get-in payload [:stream :content])) => "Hello")))))

(fact "direct external media URLs become galleries instead of broken link previews"
  (session (fn [& _] (chat/send-msg "https://example.org/meme.gif?size=large"))
    (fn [event! calls _]
      (await! (event! :message-create original))
      (get-in (first (children (last (first @calls)))) [:items 0 :media :url])
      => "https://example.org/meme.gif?size=large")))

(fact "large replies send one readable card with an exact full Markdown download"
  (let [answer (str/join "\n\n" (repeat 200 "A substantial paragraph with **Markdown**."))]
    (session (fn [& _] (chat/send-msg answer))
      (fn [event! calls _]
        (await! (event! :message-create original))
        (count @calls) => 1
        (let [payload (last (first @calls))]
          (:flags payload) => 32768
          (< (count (:content (first (children payload)))) 2100) => true
          (str/includes? (:content (first (children payload))) "Full answer") => true
          (get-in payload [:stream :filename]) => "answer.md"
          (slurp (get-in payload [:stream :content])) => answer
          (get-in (second (children payload)) [:file :url]) => "attachment://answer.md")))))

(defn- confirm-click [id payload user]
  (-> (click id (:custom_id (button payload "Confirm rerun")) user)
      (assoc-in [:message :id] "private-confirmation")
      (assoc-in [:message :flags] 0)))

(fact "Run again requires confirmation, acknowledges before execution and replaces the reply"
  (let [runs (atom [])]
    (session (fn [body user source]
               (swap! runs conj [body (:id user) (:raw-event source)])
               (chat/send-msg (str "meme " (count @runs))))
      (fn [event! calls _]
        (await! (event! :message-create original))
        (let [id (:custom_id (button (last (first @calls)) "Run again"))]
          (reset! calls [])
          (await! (event! :interaction-create (click "prepare" id "alice")))
          (count @runs) => 1
          (bit-and 64 (get-in (last (last @calls)) [:data :flags] 0)) => 0
          (let [confirmation (confirm-click "click-1" (:data (last (last @calls))) "alice")]
            (reset! calls [])
            (await! (event! :interaction-create confirmation))
            (mapv first @calls) => [:interaction :interaction-delete :edit]
          (nth (first @calls) 2) => 6
          (get-in (last (last @calls)) [:components 0 :components 0 :content]) => "meme 2"
          (mapv #(subvec % 0 2) @runs)
          => [["!meme random" "alice"] ["!meme random" "alice"]]
          (get-in @runs [1 2 :id]) => "command"
          ;; Discord can redeliver an interaction; never execute it twice.
            (await! (event! :interaction-create confirmation))
            (count @runs) => 2))))))

(fact "unconfirmed confirmation cleanup does not retry or discard a rerun"
  (let [runs (atom 0) deletes (atom 0)]
    (session (fn [& _] (swap! runs inc) (chat/send-msg "answer"))
      (fn [event! calls _]
        (await! (event! :message-create original))
        (let [id (:custom_id (button (last (first @calls)) "Run again"))]
          (await! (event! :interaction-create (click "prepare-cleanup" id "alice")))
          (let [event (confirm-click "cleanup-timeout"
                        (:data (last (last @calls))) "alice")]
            (with-redefs [messaging/delete-original-interaction-response!
                          (fn [& _]
                            (swap! deletes inc)
                            (reify clojure.lang.IBlockingDeref
                              (deref [_ _ default] default)))]
              (await! (event! :interaction-create event))
              (await! (event! :interaction-create event))
              @runs => 2
              @deletes => 1)))))))

(fact "another user cannot rerun; help and refusals are public"
  (let [runs (atom 0)]
    (with-redefs [help/docs (atom {"meme" #{"meme random # fetch a random meme"}})]
      (session (fn [& _] (swap! runs inc) (chat/send-msg "meme"))
        (fn [event! calls _]
          (await! (event! :message-create original))
          (let [payload (last (first @calls))
                again (:custom_id (button payload "Run again"))
                help-id (:custom_id (button payload "Help"))]
            (reset! calls [])
            (await! (event! :interaction-create (click "denied" again "bob")))
            @runs => 1
            (bit-and 64 (get-in (last (last @calls)) [:data :flags] 0)) => 0
            (await! (event! :interaction-create (click "help" help-id "bob")))
            @runs => 1
            (get-in (last (last @calls)) [:data :components 0 :components 0 :content])
            => "**Help · meme**\nmeme random # fetch a random meme"))))))

(fact "expired or forged button contexts never execute commands"
  (let [runs (atom 0)]
    (session (fn [& _] (swap! runs inc) (chat/send-msg "meme"))
      (fn [event! calls conn]
        (await! (event! :message-create original))
        (let [id (:custom_id (button (last (first @calls)) "Run again"))]
          (await! (event! :interaction-create
                          (assoc-in (click "forged" id "alice") [:message :id] "unrelated")))
          @runs => 1
          (swap! conn dissoc :commands)
          (await! (event! :interaction-create (click "expired" id "alice")))
          @runs => 1)))))

(fact "concurrent reruns are reserved before acknowledgement and edits supersede them"
  (let [runs (atom 0) entered (promise) release (promise)]
    (session (fn [& _] (swap! runs inc) (chat/send-msg (str "result " @runs)))
      (fn [event! calls _]
        (await! (event! :message-create original))
        (let [id (:custom_id (button (last (first @calls)) "Run again"))
              _ (await! (event! :interaction-create (click "prepare-race" id "alice")))
              confirmation (:data (last (last @calls)))
              respond messaging/create-interaction-response!]
          (with-redefs [messaging/create-interaction-response!
                        (fn [rest interaction token type & opts]
                          (if (= interaction "slow-ack")
                            (do (deliver entered true) release)
                            (apply respond rest interaction token type opts)))]
            (let [first-click (event! :interaction-create (confirm-click "slow-ack" confirmation "alice"))]
              (deref entered 3000 :timeout) => true
              (await! (event! :interaction-create (confirm-click "second-click" confirmation "alice")))
              @runs => 1
              (await! (event! :message-update (assoc original :content "!meme edited")))
              @runs => 2
              (deliver release true)
              (await! first-click)
              @runs => 2)))))))

(fact "confirmation belongs to the requester and expires when the command is edited"
  (let [runs (atom 0)]
    (session (fn [& _] (swap! runs inc) (chat/send-msg "answer"))
      (fn [event! calls _]
        (await! (event! :message-create original))
        (let [id (:custom_id (button (last (first @calls)) "Run again"))]
          (await! (event! :interaction-create (click "prepare-expiry" id "alice")))
          (let [payload (:data (last (last @calls)))]
            (await! (event! :interaction-create (confirm-click "wrong-owner" payload "bob")))
            @runs => 1
            (await! (event! :message-update (assoc original :content "!meme changed")))
            @runs => 2
            (await! (event! :interaction-create (confirm-click "old-confirmation" payload "alice")))
            @runs => 2))))))

(fact "V2 keeps Unicode and headings together through its full text budget"
  (let [text (str (apply str (repeat 1999 "x")) "😀" (apply str (repeat 1000 "y")))
        parts (components/text-parts text)]
    (str/join (map :content parts)) => text
    (count parts) => 1
    (count (:content (first parts))) => 3001
    (components/message-text
      (components/payload {:content "answer" :command-key ["channel" "command"]}))
    => "answer"
    (components/message-text (components/payload {:content "https://example.org/photo.png"}))
    => "https://example.org/photo.png"))

(fact "V2 renders 4000 characters inline and attaches an exact longer answer"
  (let [inline (apply str (repeat 4000 "x"))
        long (str inline "y")]
    (components/text-parts inline) => [{:content inline}]
    (count (components/text-parts long)) => 1
    (:file (first (components/text-parts long))) => long))

(fact "ordinary medium-length replies are one card, not legacy 2000-char cuts"
  (let [answer (str (apply str (repeat 1998 "x")) "\n\n### The verdict\nKeep this together.")]
    (session (fn [& _] (chat/send-msg answer))
      (fn [event! calls _]
        (await! (event! :message-create original))
        (count @calls) => 1
        (get-in (last (first @calls)) [:components 0 :components 0 :content])
        => answer))))

(fact "Markdown tables become aligned code blocks without losing cells"
  (let [answer (str "Before\n\n| Name | Value |\n| --- | --- |\n"
                    "| Cash | 12.00 |\n| CCY | USD |\n\nAfter")]
    (:content (first (components/text-parts answer)))
    => (str "Before\n\n```text\nName  Value\nCash  12.00\nCCY   USD\n```\n\nAfter")))

(fact "tables inside code fences are not reformatted"
  (let [code "```markdown\n| A | B |\n| --- | --- |\n| 1 | 2 |\n```"]
    (:content (first (components/text-parts code))) => code))

(fact "a table that expands beyond Discord's budget retains its exact source file"
  (let [answer (str "| Name | Value |\n| --- | --- |\n| "
                    (apply str (repeat 300 "W")) " | one |\n"
                    (str/join "\n" (repeat 20 "| a | b |")))]
    (:file (first (components/text-parts answer))) => answer
    (<= (count (:content (first (components/text-parts answer)))) 4000) => true))

(fact "sanitized progress remains publicly available after status retirement"
  (session (fn [& _]
             (discord/start-command-status! "channel" "Working")
             (discord/update-command-status! "Tools: 1/12 · 11 remaining\n#1 docs_search — success")
             (discord/finish-command-status!)
             (chat/send-msg "Final answer"))
    (fn [event! calls _]
      (await! (event! :message-create original))
      (let [payload (last (first (filter #(= :create (first %)) @calls)))
            id (:custom_id (button payload "Show details"))]
        (boolean id) => true
        (await! (event! :interaction-create (click "details" id "bob")))
        (get-in (last (last @calls)) [:data :flags]) => 32768
        (get-in (last (last @calls)) [:data :components 0 :components 0 :content])
        => "**Tool details**\nTools: 1/12 · 11 remaining\n#1 docs_search — success"))))
