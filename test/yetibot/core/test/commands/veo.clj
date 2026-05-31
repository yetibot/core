(ns yetibot.core.test.commands.veo
  (:require [midje.sweet :refer [facts fact => =not=> provided anything throws]]
            [midje.checkers :refer [contains]]
            [yetibot.core.commands.veo :as veo]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.chat :as chat]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(facts "about veo command"
  (fact "returns an error when Gemini is not configured"
    (veo/veo-cmd {:match "veo a cute cat" :chat-source {}})
    => {:result/error "Gemini API is not configured. Set `gemini.key` in config."}
    (provided (gemini/configured?) => false))

  (fact "initiates async video generation and returns startup message"
    (binding [chat/*adapter* :mock-adapter
              chat/*target* :mock-target
              chat/*thread-ts* :mock-thread-ts]
      ;; no-op the future so the unit test never makes a real Veo HTTP call
      (with-redefs [clojure.core/future-call (fn [_] nil)]
        (veo/veo-cmd {:match "a cool robot dancing" :chat-source {} :user {:id "user123"}}))
      => {:result/value "🎥 Grug start generating video for \"a cool robot dancing\". This take some time (30s to 3m)..."}
      (provided
        (gemini/configured?) => true
        (image-input/extract-images "a cool robot dancing" {}) => {:prompt "a cool robot dancing" :image-urls nil})))

  (fact "future executes successfully, generates and posts video link"
    (binding [chat/*adapter* :mock-adapter
              chat/*target* :mock-target
              chat/*thread-ts* :mock-thread-ts]
      (with-redefs [clojure.core/future-call (fn [f] (f))]
        (veo/veo-cmd {:match "a cool robot dancing" :chat-source {} :user {:id "user123"}}))
      => {:result/value "🎥 Grug start generating video for \"a cool robot dancing\". This take some time (30s to 3m)..."}
      (provided
        (gemini/configured?) => true
        (image-input/extract-images "a cool robot dancing" {}) => {:prompt "a cool robot dancing" :image-urls nil}
        (gemini/generate-video "a cool robot dancing" nil) => :video-bytes
        (store-image! :video-bytes) => "img123"
        (gemini/yetibot-base-url) => "http://localhost:3000"
        (chat/send-msg "<@user123>: http://localhost:3000/generated-images/img123.mp4") => anything)))

  (fact "future handles failure, posts error message"
    (binding [chat/*adapter* :mock-adapter
              chat/*target* :mock-target
              chat/*thread-ts* :mock-thread-ts]
      (with-redefs [clojure.core/future-call (fn [f] (f))]
        (veo/veo-cmd {:match "a cool robot dancing" :chat-source {} :user {:id "user123"}}))
      => {:result/value "🎥 Grug start generating video for \"a cool robot dancing\". This take some time (30s to 3m)..."}
      (provided
        (gemini/configured?) => true
        (image-input/extract-images "a cool robot dancing" {}) => {:prompt "a cool robot dancing" :image-urls nil}
        (gemini/generate-video "a cool robot dancing" nil) => (throw (Exception. "API Error"))
        (chat/send-msg "<@user123>: Video generation failed: API Error") => anything))))

(facts "about redact"
  (fact "masks a leaked api key embedded in an error"
    (veo/redact "predictLongRunning?key=AIzaSyABC123 Read timed out") => (contains "key=***"))
  (fact "does not expose the key"
    (veo/redact "?key=AIzaSyABC123 boom") =not=> (contains "AIzaSyABC123"))
  (fact "leaves a clean message untouched"
    (veo/redact "Video generation failed: API Error") => "Video generation failed: API Error")
  (fact "tolerates nil"
    (veo/redact nil) => nil))
