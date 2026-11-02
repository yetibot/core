(ns yetibot.core.test.adapters.discord-inspection-projection
  (:require [clojure.string :as str]
            [midje.sweet :refer [fact =>]]
            [yetibot.core.adapters.discord-components :as components]
            [yetibot.core.adapters.discord-inspection :as inspect]
            [yetibot.core.util.public-result :as public-result]))

(defn- capture [value]
  (let [state (atom (inspect/initial))]
    (binding [public-result/*capture* #(inspect/capture! state %)]
      (public-result/observe!
        (public-result/reviewed {:result/public-data value})))
    (swap! state assoc :sealed? true :elapsed "1.2s") state))

(defn- text [page]
  (str/join "\n" (keep :content (get-in page [:components 0 :components]))))

(fact "public capture does not mutate pipeline data or trust serialized tags"
  (let [value {:result/value "Answer" :result/data {:private "SECRET"}}
        seen (atom [])]
    (binding [public-result/*capture* #(swap! seen conj %)]
      (identical? value (public-result/observe! value)) => true
      (:public-data? (first @seen)) => false
      (public-result/observe!
        (with-meta (assoc value :result/public-data {:secret "PRIVATE"})
                   {::public-result/reviewed (Object.)}))
      (:public-data? (last @seen)) => false)
    (binding [public-result/*capture* (fn [_] (throw (Exception. "private")))]
      (public-result/observe! value) => value)))

(fact "snapshots bound infinite collections, depth, text and unsupported objects"
  (let [state (capture {:array (range)
                        :deep (nth (iterate #(hash-map :child %) "END") 30)
                        :text (apply str (repeat 3000 "😀"))
                        :authorization "PRIVATE-CREDENTIAL"
                        :object (Object.)})
        data (first (:data @state))
        page (inspect/payload state ["200" "100"] "d" 0 0 nil)]
    (count (get data "array")) => 32
    (count (get data "text")) => 1200
    (str/includes? (pr-str data) "END") => false
    (str/includes? (pr-str data) "PRIVATE-") => false
    (get data "object") => "[unavailable]"
    (< (count (pr-str @state)) 50000) => true
    (<= (count (text page)) components/max-text-length) => true
    (str/includes? (text page) "shortened") => true
    (let [before @state]
      (inspect/capture! state {:data? true :public-data? true
                              :public-data {:late "not retained"}})
      @state => before)))

(fact "paging and nested controls resolve only snapshot nodes"
  (let [state (capture (mapv #(hash-map :value %) (range 20)))
        page (inspect/payload state ["200" "100"] "d" 0 0 nil)
        children (get-in page [:components 0 :components])
        menu (first (:components (some #(when (= 3 (get-in % [:components 0 :type]))
                                                         %) children)))
        next (some #(when (= "Next" (:label %)) %)
                   (mapcat :components children))
        control (inspect/parse-control (:custom_id next))]
    (count (:options menu)) => 8
    (:page control) => 1
    (every? #(<= (count (:custom_id %)) 100)
            (concat [menu] (filter :custom_id (mapcat :components children))))
    => true
    (some? (inspect/payload state ["200" "100"] "d" 0 1 nil)) => true
    (inspect/payload state ["200" "100"] "d" 9999 0 nil) => nil
    (inspect/payload state ["200" "100"] "d" 0 99 nil) => nil
    (inspect/payload state ["200" "100"] "d" 0 0 ["9999"]) => nil
    (inspect/payload state ["200" "100"] "d" 0 0 []) => nil
    (inspect/payload state ["200" "100"] "d" 0 0 ["1" "2"]) => nil))

(fact "all main controls fit one row and data cannot escape its JSON fence"
  (let [main (components/payload {:content "Answer" :command-key ["200" "100"]
                                   :details "Host-owned detail" :data? true
                                   :error? true})
        state (capture {:value "```\n@everyone\n```"})
        page (inspect/payload state ["200" "100"] "d" 0 0 nil)]
    (count (get-in main [:components 0 :components 1 :components])) => 5
    (count (re-seq #"```" (text page))) => 2
    (:allowed_mentions page) => {:parse [] :replied_user false}))
