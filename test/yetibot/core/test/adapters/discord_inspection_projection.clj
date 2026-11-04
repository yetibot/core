(ns yetibot.core.test.adapters.discord-inspection-projection
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
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

(defn- maps [data] (filter map? (tree-seq coll? seq data)))
(defn- text [page] (components/message-text page))
(defn- button [data label]
  (some #(when (= label (:label %)) %) (maps data)))
(defn- base [state content]
  (-> (components/payload {:content content :command-key ["200" "100"]
                            :data? true :data-token (:token @state)
                            :elapsed "1.2s"})
      (dissoc :allowed-mentions)
      (assoc :allowed_mentions {:parse [] :replied_user false})))
(defn- page [state depth n]
  (inspect/inline-data-payload state ["200" "100"] (base state "Answer") depth n))
(defn- preview [data]
  (some #(when (str/starts-with? (or (:content %) "") "**Explore data**")
           (second (re-find #"(?s)```json\n(.*?)\n```" (:content %)))) (maps data)))

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

(fact "public text requires review and retains every Unicode character within bounds"
  (doseq [reviewed? [false true]]
    (let [state (atom (inspect/initial))
          full (str (apply str (repeat 11000 "😀")) "\nEND")
          result {:result/public-text full}]
      (binding [public-result/*capture* #(inspect/capture! state %)]
        (public-result/observe! (if reviewed? (public-result/reviewed result)
                                   result)))
      (if reviewed?
        (let [pages (get-in @state [:text-pages :pages])]
          (apply str pages) => full
          (every? #(<= (count %) 3000) pages) => true
          (not-any? #(Character/isHighSurrogate (last %)) pages) => true
          (swap! state assoc :sealed? true)
          (inspect/text-page-options state ["200" "100"] full) => map?
          (inspect/text-page-options state ["200" "100"] "Changed") => nil
          (let [before @state]
            (inspect/capture! state {:public-text (str full "LATE")})
            @state => before))
        (:text-pages @state) => nil)))
  (doseq [broken [(str (apply str (repeat 5000 "x")) (char 0xd800))
                  (str (char 0xdc00) (apply str (repeat 5000 "x")))]]
    (let [state (atom (inspect/initial))]
      (inspect/capture! state {:public-text broken})
      (:text-pages @state) => nil))
  (doseq [size [3968 262144 262145]]
    (let [state (atom (inspect/initial)) text (apply str (repeat size "x"))]
      (inspect/capture! state {:public-text text})
      (boolean (:text-pages @state)) => (= size 262144)))
  (doseq [id ["yb:page:200:100:0000000000000000:-1"
             "yb:page:200:100:0000000000000000:1000"
             "yb:page:other:100:0000000000000000:0"
             "yb:page:200:100:bad:0"]]
    (inspect/parse-control id) => nil))

(fact "text navigation closes and reopens code fences without changing downloads"
  (doseq [fence ["```" "~~~"]]
    (let [full (str "Intro\n\n" fence "typescript\n"
                    (apply str (repeat 500 "const value = 1;\n"))
                    fence "\n\nTail")
          state (atom (inspect/initial))
          _ (inspect/capture! state {:public-text full})
          _ (swap! state assoc :sealed? true)
          options (inspect/text-page-options state ["200" "100"] full)
          base (components/payload (assoc options :command-key ["200" "100"]))
          pages (get-in @state [:text-pages :pages])]
      (apply str pages) => full
      (doseq [n (range (count pages))]
        (let [payload (inspect/inline-text-payload state ["200" "100"] base n)
              content (get-in payload [:components 0 :components 0 :content])]
          (even? (count (re-seq #"(?m)^(?:`{3,}|~{3,})" content))) => true
          (str/includes? content (str "Page " (inc n) " of")) => true
          (<= (count content) components/max-text-length) => true)))))

(fact "overlong Markdown fence markers keep the ordinary download fallback"
  (let [state (atom (inspect/initial))
        full (str (apply str (repeat 65 "`")) "\n"
                  (apply str (repeat 5000 "x"))) ]
    (inspect/capture! state {:public-text full})
    (:text-pages @state) => nil))

(fact "snapshots bound infinite collections, depth, text and unsupported objects"
  (let [state (capture {:array (range)
                        :deep (nth (iterate #(hash-map :child %) "END") 30)
                        :text (apply str (repeat 3000 "😀"))
                        :authorization "PRIVATE-CREDENTIAL"
                        :object (Object.)})
        data (first (:data @state))
        view (page state nil 0)]
    (count (get data "array")) => 32
    (count (get data "text")) => 1200
    (str/includes? (pr-str data) "END") => false
    (str/includes? (pr-str data) "PRIVATE-") => false
    (get data "object") => "[unavailable]"
    (< (count (pr-str @state)) 50000) => true
    (<= (count (text view)) components/max-text-length) => true
    (str/includes? (text view) "shortened") => true
    (let [before @state]
      (inspect/capture! state {:data? true :public-data? true
                              :public-data {:late "not retained"}})
      @state => before)))

(fact "whole-tree depth reveals every branch and resets paging"
  (let [state (capture {:a {:nested {:leaf "alpha"}}
                        :b {:nested {:leaf "beta"}}})
        initial (page state nil 0)
        expanded (page state 3 0)]
    (str/includes? (text initial) "Depth 2") => true
    (string? (get-in (json/read-str (preview initial)) ["a" "nested"])) => true
    (string? (get-in (json/read-str (preview initial)) ["b" "nested"])) => true
    (get-in (json/read-str (preview expanded)) ["a" "nested" "leaf"]) => "alpha"
    (get-in (json/read-str (preview expanded)) ["b" "nested" "leaf"]) => "beta"
    (:disabled (button expanded "Expand")) => true
    (:disabled (button (page state 1 0) "Collapse")) => true
    (:depth (inspect/parse-control (:custom_id (button expanded "Collapse")))) => 2
    (:page (inspect/parse-control (:custom_id (button expanded "Collapse")))) => 0
    (page state 0 0) => nil
    (page state 4 0) => nil
    (page state 2 -1) => nil
    (page state 2 99) => nil
    (page state "2" 0) => nil))

(fact "paging preserves every JSON character, typed value and Unicode pair"
  (let [state (capture (mapv #(hash-map :index % :label
                                       (str % (apply str (repeat 80 "🙂`\\\""))))
                            (range 20)))
        initial (page state 2 0)
        pages (loop [n 0 result []]
                (let [view (page state 2 n)
                      result (conj result (preview view))]
                  (<= (count (text view)) 4000) => true
                  (count (re-seq #"```" (preview view))) => 0
                  (boolean (re-find #"[\uD800-\uDBFF]$" (preview view))) => false
                  (if (:disabled (button view "Next")) result
                      (recur (inc n) result))))]
    (> (count pages) 1) => true
    (json/read-str (apply str pages)) => (first (:data @state))
    (:page (inspect/parse-control (:custom_id (button initial "Next")))) => 1
    (:disabled (button initial "Previous")) => true
    (every? #(<= (count %) 100) (keep :custom_id (maps initial))) => true
    (apply distinct? (keep :custom_id (maps initial))) => true
    (inspect/parse-control "yb:tree:200:100:0123456789abcdef:e:9:0") => nil
    (inspect/parse-control "yb:tree:200:100:0123456789abcdef:e:2:100") => nil
    (inspect/parse-control "yb:tree:200:100:0123456789abcdef:e:2:/private") => nil))

(fact "null, empty, boolean, numeric and array values retain their JSON types"
  (let [value {:empty "" :blank "  " :nil nil :false false :number 3
               :list [1 nil false ""] :object {} :array []}
        state (capture value)]
    (json/read-str (preview (page state nil 0))) => (first (:data @state))
    (:allowed_mentions (page state nil 0)) => {:parse [] :replied_user false}
    (some #(when (= 3 (:type %)) %) (maps (page state nil 0))) => nil))

(fact "long fenced answers, media and files survive bounded inline expansion"
  (doseq [answer [(apply str (repeat 1800 "🙂"))
                  (str "\n" (apply str (repeat 900 "`")) "\n"
                       (apply str (repeat 1500 "x")) "\n"
                       (apply str (repeat 900 "`")))]]
    (let [state (capture {:public (apply str (repeat 1000 "x"))})
          original (-> (base state answer)
                       (update-in [:components 0 :components] into
                         [{:type 12 :items [{:media {:url "attachment://image.png"}}]}
                          {:type 13 :file {:url "attachment://answer.md"}}]))
          expanded (inspect/inline-data-payload state ["200" "100"] original nil 0)]
      (some? expanded) => true
      (<= (count (text expanded)) 4000) => true
      (<= (count (filter :type (maps expanded))) 40) => true
      (count (re-seq #"(?m)^-# [0-9]+[.][0-9]s$" (text expanded))) => 1
      (filter #(#{12 13} (:type %)) (maps expanded))
      => (filter #(#{12 13} (:type %)) (maps original))
      (contains? expanded :stream) => false
      (contains? expanded :attachments) => false
      (some? (button expanded "Hide data")) => true
      (button original "Hide data") => nil)))
