(ns yetibot.core.test.commands.collection-public-data
  (:require [midje.sweet :refer [fact =>]]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.echo]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.public-result :as public]))

(def rows ["b" "a" "b" "c"])
(def raw-rows (mapv #(hash-map :slot % :private "RAW-PRIVATE") (range 4)))
(def public-rows (mapv #(hash-map :id % :summary (nth rows %)) (range 4)))

(defn fixture [_]
  (public/reviewed {:result/value rows :result/data {:items raw-rows}
                    :result/collection-path [:items]
                    :result/public-data public-rows}))
(cmd-hook #"publicfixture" _ fixture)

(defn numbers [_]
  (public/reviewed {:result/value ["10" "2" "1" "2"]
                    :result/data raw-rows :result/public-data public-rows}))
(cmd-hook #"numericpublicfixture" _ numbers)

(defn unreviewed [_]
  {:result/value rows :result/data {:items raw-rows}
   :result/collection-path [:items] :result/public-data public-rows})
(cmd-hook #"unreviewedfixture" _ unreviewed)

(defn detail [{:keys [data]}]
  (public/reviewed {:result/value (str "detail " (:slot data))
                    :result/data data
                    :result/public-data {:id (:slot data) :detail true}}))
(cmd-hook #"publicdetail" _ detail)

(defn nested [_]
  (public/reviewed
    {:result/value [["a\nb" "c"] ["d"]]
     :result/data [[(nth raw-rows 0) (nth raw-rows 1)] [(nth raw-rows 2)]]
     :result/public-data [[(nth public-rows 0) (nth public-rows 1)]
                          [(nth public-rows 2)]]}))
(cmd-hook #"nestedpublicfixture" _ nested)

(defn run [text]
  (let [seen (atom [])]
    (with-redefs [channel/settings-for-chat-source (constantly {})]
      (let [result (binding [public/*capture* #(swap! seen conj %)]
                     (handler/handle-parsed-expr
                       {:adapter :discord :room "test"}
                       {:id "u" :username "u"} {:id "bot"}
                       (parser/parser text)))]
        {:result result :snapshots (mapv :public-data
                                    (filter :public-data? @seen))}))))

(fact "collection values, raw data and reviewed data use the same selections"
  (with-redefs [clojure.core/rand-int (constantly 1)
                clojure.core/shuffle reverse]
    (doseq [[command indices single?]
            [["random" [1] true] ["head" [0] true] ["tail" [3] true]
             ["head 2" [0 1] false] ["take 2" [0 1] false]
             ["tail 2" [2 3] false] ["rest" [1 2 3] false]
             ["droplast" [0 1 2] false] ["reverse" [3 2 1 0] false]
             ["sort" [1 0 2 3] false] ["shuffle" [3 2 1 0] false]
             ["grep b" [0 2] false] ["grep -v b" [1 3] false]
             ["grep -C 1 a" [0 1 2] false] ["set" [0 1 3] false]
             ["reverse | head 2 | random" [2] true]]]
      (let [{:keys [result snapshots]} (run (str "publicfixture | " command))
            select #(let [v (mapv % indices)] (if single? (first v) v))]
        (fact (:value result) => (select rows))
        (fact (:data result) => (select raw-rows))
        (fact snapshots => [(select public-rows)])))))

(fact "only final reviewed output is captured, not the upstream catalog"
  (:snapshots (run "publicfixture | head | publicdetail"))
  => [{:id 0 :detail true}]
  (:snapshots (run "publicfixture | tail 2 | xargs publicdetail"))
  => [[{:id 2 :detail true} {:id 3 :detail true}]]
  (with-redefs [clojure.core/rand-int (constantly 1)]
    (:snapshots (run "publicfixture | repeat 3 random"))
    => [(vec (repeat 3 (nth public-rows 1)))])
  (:snapshots (run "publicfixture | count")) => [4]
  (:snapshots (run "publicfixture | grep missing")) => [[]])

(fact "unreviewed data and unknown transformations cannot gain public approval"
  (doseq [command ["unreviewedfixture | head" "unreviewedfixture | reverse"
                   "publicfixture | echo changed" "echo `publicfixture`"]]
    (fact (:snapshots (run command)) => [])))

(fact "flatten repeats matching metadata when a value expands into lines"
  (let [{:keys [result snapshots]} (run "nestedpublicfixture | flatten")]
    (:value result) => ["a" "b" "c" "d"]
    (:data result) => (mapv raw-rows [0 0 1 2])
    snapshots => [(mapv public-rows [0 0 1 2])]))

(fact "numeric sorts and aggregate formatting retain corresponding metadata"
  (let [{:keys [result snapshots]} (run "numericpublicfixture | sortnum")]
    (:value result) => ["1" "2" "2" "10"]
    (:data result) => (mapv raw-rows [2 1 3 0])
    snapshots => [(mapv public-rows [2 1 3 0])])
  (doseq [op ["sum" "join" "unwords" "unletters" "raw"]]
    (fact (:snapshots (run (str "numericpublicfixture | head 2 | " op)))
          => [(subvec public-rows 0 2)])))

(fact "projection forwarding and aggregation reject forged metadata"
  (let [forged (with-meta {:result/public-data public-rows}
                         {::public/reviewed (Object.)})
        result {:result/value "x" :result/data {:private "RAW-PRIVATE"}}]
    (public/projection forged) => nil
    (public/projection
      (public/transform-collection {:opts rows :public-result forged}
                                   result first)) => nil
    (public/projection
      (public/forward {:public-result forged} result)) => nil
    (:result/public-data
      (public/aggregate result
        [(public/reviewed {:result/public-data {:id 1}}) forged]))
    => [{:id 1} nil]
    (public/projection
      (public/transform-collection
        {:opts ["one"] :public-result
         (public/reviewed {:result/public-data public-rows})} result first))
    => nil))
