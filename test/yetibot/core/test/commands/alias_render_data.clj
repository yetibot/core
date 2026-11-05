(ns yetibot.core.test.commands.alias-render-data
  (:require [clojure.string :as str]
            [midje.sweet :refer [fact =>]]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.echo]
            [yetibot.core.commands.render :as render]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.public-result :as public]))

(def rows [{:city "warm" :temp 20 :private "PRIVATE-RAW"}
           {:city "cold" :temp 10 :private "PRIVATE-RAW"}])
(def safe-rows (mapv #(select-keys % [:city :temp]) rows))

(defn fixture [_]
  (public/reviewed {:result/value ["warm" "cold"] :result/data rows
                    :result/public-data safe-rows}))

(defn run [text]
  (let [seen (atom [])]
    (binding [public/*capture* #(swap! seen conj %)]
      (let [result (handler/handle-parsed-expr
                     {:adapter :discord :uuid :test :room "test"}
                     {:id "u" :username "u"} {:id "bot"}
                     (parser/parser text))]
        {:result result :snapshots (mapv :public-data
                                     (filter :public-data? @seen))}))))

(fact "render carries the original paired records, not its template wrapper"
  (let [result (render/render-cmd
                 {:match "{{temp}} {{city}}" :data rows
                  :public-result
                  (public/reviewed {:result/public-data safe-rows})})]
    (:result/value result) => ["20 warm" "10 cold"]
    (:result/data result) => rows
    (:result/public-data (public/projection result)) => safe-rows)
  (public/projection
    (render/render-cmd {:match "{{temp}}" :data (first rows)
                        :public-result {:result/public-data safe-rows}}))
  => nil)

(fact "real nested aliases preserve raw/public data through render and sort"
  (try (deref alias/loader) (catch Exception _ nil))
  (with-redefs [hooks/hooks (atom @hooks/hooks)
                hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                channel/settings-for-chat-source (constantly {})
                handler/add-user-message-to-history (fn [& _])
                history/add (fn [& _])]
    (hooks/cmd-hook #"aliasdatafixture" _ fixture)
    (#'alias/wire-alias {:cmd-name "innerdata"
                        :cmd "aliasdatafixture | render {{temp}} {{city}}"})
    (#'alias/wire-alias {:cmd-name "outerdata"
                        :cmd "innerdata | sort"})
    (let [{:keys [result snapshots]} (run "outerdata")]
      (:value result) => ["10 cold" "20 warm"]
      (:data result) => (vec (reverse rows))
      snapshots => [(vec (reverse safe-rows))]
      (str/includes? (pr-str snapshots) "PRIVATE") => false)
    (fact "a formatting-only alias retains the caller's reviewed input"
      (#'alias/wire-alias {:cmd-name "formatdata"
                          :cmd "render {{temp}} {{city}}"})
      (let [{:keys [result snapshots]}
            (run "aliasdatafixture | formatdata | sort")]
        (:value result) => ["10 cold" "20 warm"]
        (:data result) => (vec (reverse rows))
        snapshots => [(vec (reverse safe-rows))]))
    (fact "aliases preserve subsequent selections and raw-data consumption"
      (let [{:keys [result snapshots]} (run "outerdata | head")]
        (:value result) => "10 cold"
        (:data result) => (second rows)
        snapshots => [(second safe-rows)]))
    (fact "unreviewed alias results do not regain an earlier projection"
      (#'alias/wire-alias {:cmd-name "nodataalias" :cmd "echo changed"})
      (:snapshots (run "aliasdatafixture | nodataalias")) => [])
    (fact "default chat results do not carry private pipeline state"
      (binding [yetibot.core.interpreter/*chat-source*
                {:adapter :discord :uuid :test :room "test"}]
        (contains? (first (handler/record-and-run-raw
                            "!aliasdatafixture" {:id "u"} {:id "bot"}))
                   :pipeline-result)) => false)))
