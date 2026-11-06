(ns yetibot.core.test.util.ai-usage-transports
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.core.commands.agent :as agent]
            [yetibot.core.db.ai-usage :as store]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.test.support.ai-usage :refer [with-store metrics]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def text-response
  {:status 200 :body {:usage {:input_tokens 10 :output_tokens 2
                             :cost_in_usd_ticks 7}
                      :output [{:type "message"
                                :content [{:text "PRIVATE-ANSWER"}]}]}})

(fact "legacy xAI text and image rewriting each record their own wire receipt"
  (with-store
    (fn [rows _]
      (let [calls (atom 0)]
        (with-redefs [xai/config {:key "PRIVATE-KEY"}
                      http/post
                      (fn [url opts]
                        (swap! calls inc)
                        (count (filter #(= "pending" (:state %)) (rows))) => 1
                        (:redirect-strategy opts) => :none
                        (if (string/ends-with? url "/responses") text-response
                          {:status 200 :body {:data [{:b64_json "PRIVATE-BYTES"}]}}))]
          (:data (xai/generate-image "PRIVATE-PROMPT")) => "PRIVATE-BYTES")
        @calls => 2
        (count (rows)) => 2
        (set (map #(get-in (metrics %) [0 :cost_kind]) (rows)))
        => #{"reported" "estimate"}
        (set (map :operation (rows))) => #{"text" "image"}
        (string/includes? (pr-str (rows)) "PRIVATE") => false))))

(fact "Gemini images retain usage but never raw image bytes"
  (with-store
    (fn [rows _]
      (with-redefs [gemini/config {:key "PRIVATE-KEY"}
                    gemini/record-image-generated! (fn [& _])
                    gemini/cost-per-image (constantly 0.04M)
                    http/post
                    (fn [& _]
                      (:state (first (rows))) => "pending"
                      {:status 200
                       :body {:usageMetadata {:promptTokenCount 12}
                              :candidates [{:content {:parts
                                          [{:inlineData {:data "PRIVATE-BYTES"
                                                         :mimeType "image/png"}}]}}]}})]
        (:data (gemini/generate-image "PRIVATE-PROMPT")) => "PRIVATE-BYTES")
      (metrics (first (rows)))
      => [{:model gemini/default-model :requests 1 :input_tokens 12
           :images 1 :usd 0.04 :cost_kind "estimate"}]
      (string/includes? (pr-str (rows)) "PRIVATE") => false)))

(fact "unknown Gemini image models keep units but never inherit default pricing"
  (with-store
    (fn [rows _]
      (with-redefs [gemini/config {:key "PRIVATE-KEY"}
                    gemini/record-image-generated! (fn [& _])
                    http/post
                    (fn [& _]
                      {:status 200 :body {:candidates
                                         [{:content {:parts [{:inlineData
                                                              {:data "PRIVATE-BYTES"
                                                               :mimeType "image/png"}}]}}]}})]
        (with-redefs [gemini/gemini-model (constantly "gemini-future-image")]
          (:data (gemini/generate-image "PRIVATE"))) => "PRIVATE-BYTES"
        (get-in (metrics (first (rows))) [0 :images]) => 1
        (get-in (metrics (first (rows))) [0 :usd]) => nil
        (get-in (metrics (first (rows))) [0 :cost_kind]) => nil
        (with-redefs [gemini/config {:key "PRIVATE-KEY" :cost {:per 0.14M}}
                      gemini/gemini-model (constantly "gemini-future-image")]
          (gemini/generate-image "PRIVATE"))
        (set (map #(get-in (metrics %) [0 :usd]) (rows))) => #{nil 0.14}))))

(fact "Veo tracks one generation, not its status polls or downloads"
  (with-store
    (fn [rows _]
      (with-redefs [gemini/config {:key "PRIVATE-KEY"}
                    gemini/record-image-generated! (fn [& _])
                    gemini/veo-start
                    (fn [& _] (:state (first (rows))) => "pending" "fixture-op")
                    gemini/veo-poll
                    (fn [& _]
                      {:response {:generateVideoResponse
                                  {:generatedSamples [{:video {:uri "fixture"}}]}}})
                    gemini/veo-download (fn [& _] "PRIVATE-BYTES")]
        (:data (gemini/generate-video "PRIVATE-PROMPT" nil
                                      gemini/default-veo-model 4))
        => "PRIVATE-BYTES")
      (count (rows)) => 1
      (:operation (first (rows))) => "video"
      (get-in (metrics (first (rows))) [0 :requests]) => 1
      (get-in (metrics (first (rows))) [0 :seconds]) => 4
      (string/includes? (pr-str (rows)) "PRIVATE") => false)))

(fact "Gemini CLI is a session with actual native per-model request counts"
  (let [dir (.toFile (Files/createTempDirectory "yetibot-usage-cli-"
                       (make-array FileAttribute 0)))
        executable (io/file dir "fixture-cli")
        marker (io/file dir "spawned")
        result {:response "PRIVATE-ANSWER"
                :stats {:models {:gemini-fixture
                                 {:api {:totalRequests 2}
                                  :tokens {:prompt 12 :input 9 :cached 3
                                           :candidates 5 :thoughts 1}}}}}]
    (try
      (spit executable (str "#!/bin/sh\ntouch '" marker "'\nprintf '%s' '"
                            (json/generate-string result) "'\n"))
      (.setExecutable executable true)
      (with-store
        (fn [rows _]
          (with-redefs [agent/cli-bin (constantly (str executable))
                        agent/model (constantly "gemini-fixture")
                        agent/gemini-key (constantly "PRIVATE-KEY")
                        agent/build-agent-prompt (fn [& _] "PRIVATE-PROMPT")]
            (:response (agent/run-gemini-agent dir "PRIVATE-PROMPT" nil nil nil))
            => "PRIVATE-ANSWER"
            (.exists marker) => true
            (count (rows)) => 1
            (:event_kind (first (rows))) => "session"
            (get-in (metrics (first (rows))) [0 :requests]) => 2
            (get-in (metrics (first (rows))) [0 :usd]) => nil
            (string/includes? (pr-str (rows)) "PRIVATE") => false
            (io/delete-file marker)
            (with-redefs [store/begin! (fn [& _] (throw (Exception. "PRIVATE")))]
              (try (agent/run-gemini-agent dir "PRIVATE-PROMPT" nil nil nil)
                   (catch Exception e (.getMessage e)))
              => "AI usage tracking is unavailable. No AI request was sent."
              (.exists marker) => false))))
      (finally (doseq [file (reverse (file-seq dir))] (io/delete-file file))))))
