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
        (gemini/generate-video "a cool robot dancing" nil "veo-3.1-lite-generate-preview" 4) => :video-bytes
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
        (gemini/generate-video "a cool robot dancing" nil "veo-3.1-lite-generate-preview" 4) => (throw (Exception. "API Error"))
        (chat/send-msg "<@user123>: Video generation failed: API Error") => anything))))

(facts "about veo model and prompt parsing"
  (fact "correctly parses lite preset"
    (veo/parse-model-and-prompt "lite a cute cat")
    => {:model "veo-3.1-lite-generate-preview" :duration 4 :prompt "a cute cat"})

  (fact "correctly parses fast preset"
    (veo/parse-model-and-prompt "fast a cute cat")
    => {:model "veo-3.1-fast-generate-preview" :duration 4 :prompt "a cute cat"})

  (fact "correctly parses gigaveo preset"
    (veo/parse-model-and-prompt "gigaveo a cute cat")
    => {:model "veo-3.1-generate-preview" :duration 8 :prompt "a cute cat"})

  (fact "correctly parses better preset"
    (veo/parse-model-and-prompt "better a cute cat")
    => {:model "veo-3.1-generate-preview" :duration 8 :prompt "a cute cat"})

  (fact "ignores preset when no prompt text follows"
    (veo/parse-model-and-prompt "lite")
    => {:prompt "lite"})

  (fact "returns raw prompt when no preset is matched"
    (veo/parse-model-and-prompt "a cute cat")
    => {:prompt "a cute cat"}))

(facts "about dynamic veo model and duration selection"
  (fact "veo-cmd parses 'lite' option and generates with lite model"
    (binding [chat/*adapter* :mock-adapter
              chat/*target* :mock-target
              chat/*thread-ts* :mock-thread-ts]
      (with-redefs [clojure.core/future-call (fn [f] (f))]
        (veo/veo-cmd {:match "lite a cool robot" :chat-source {} :user {:id "user123"} :cmd "veo"}))
      => {:result/value "🎥 Grug start generating video for \"a cool robot\". This take some time (30s to 3m)..."}
      (provided
        (gemini/configured?) => true
        (image-input/extract-images "lite a cool robot" {}) => {:prompt "lite a cool robot" :image-urls nil}
        (gemini/generate-video "a cool robot" nil "veo-3.1-lite-generate-preview" 4) => :video-bytes
        (store-image! :video-bytes) => "img123"
        (gemini/yetibot-base-url) => "http://localhost:3000"
        (chat/send-msg "<@user123>: http://localhost:3000/generated-images/img123.mp4") => anything)))

  (fact "veo-cmd with gigaveo cmd generates 8s video with flagship model"
    (binding [chat/*adapter* :mock-adapter
              chat/*target* :mock-target
              chat/*thread-ts* :mock-thread-ts]
      (with-redefs [clojure.core/future-call (fn [f] (f))]
        (veo/veo-cmd {:match "a cool robot" :chat-source {} :user {:id "user123"} :cmd "gigaveo"}))
      => {:result/value "🎥 Grug start generating video for \"a cool robot\". This take some time (30s to 3m)..."}
      (provided
        (gemini/configured?) => true
        (image-input/extract-images "a cool robot" {}) => {:prompt "a cool robot" :image-urls nil}
        (gemini/generate-video "a cool robot" nil "veo-3.1-generate-preview" 8) => :video-bytes
        (store-image! :video-bytes) => "img123"
        (gemini/yetibot-base-url) => "http://localhost:3000"
        (chat/send-msg "<@user123>: http://localhost:3000/generated-images/img123.mp4") => anything))))

(facts "about redact"
  (fact "masks a leaked api key embedded in an error"
    (veo/redact "predictLongRunning?key=AIzaSyABC123 Read timed out") => (contains "key=***"))
  (fact "does not expose the key"
    (veo/redact "?key=AIzaSyABC123 boom") =not=> (contains "AIzaSyABC123"))
  (fact "leaves a clean message untouched"
    (veo/redact "Video generation failed: API Error") => "Video generation failed: API Error")
  (fact "tolerates nil"
    (veo/redact nil) => nil))

(facts "about video extraction"
  (fact "extracts inline mp4 URLs and cleans prompt"
    (veo/extract-videos "a cool robot http://example.com/robot.mp4" {})
    => {:prompt "a cool robot" :video-urls ["http://example.com/robot.mp4"]})

  (fact "extracts video attachments and removes them from chat source"
    (let [chat-source {:raw-event {:attachments [{:filename "video.mp4" :url "http://example.com/video.mp4" :content-type "video/mp4"}]}}]
      (veo/extract-videos "a cool robot" chat-source)
      => {:prompt "a cool robot" :video-urls ["http://example.com/video.mp4"]}))

  (fact "handles multiple input sources and removes video attachments from chat-source"
    (let [chat-source {:raw-event {:attachments [{:filename "video.mp4" :url "http://example.com/video.mp4" :content-type "video/mp4"}
                                                 {:filename "img.png" :url "http://example.com/img.png" :content-type "image/png"}]}}]
      (veo/extract-videos "a cool robot" chat-source)
      => {:prompt "a cool robot" :video-urls ["http://example.com/video.mp4"]})))

(facts "about video extension with veo-cmd"
  (fact "veo-cmd prepends startup message, upgrades lite model, sets duration to 8, and passes video-url to generate-video"
    (binding [chat/*adapter* :mock-adapter
              chat/*target* :mock-target
              chat/*thread-ts* :mock-thread-ts]
      (with-redefs [clojure.core/future-call (fn [f] (f))]
        (veo/veo-cmd {:match "lite a cool robot http://example.com/input.mp4" :chat-source {} :user {:id "user123"} :cmd "veo"}))
      => {:result/value "💸 🔥  time to burn some money!\n🎥 Grug start generating video for \"a cool robot\". This take some time (30s to 3m)..."}
      (provided
        (gemini/configured?) => true
        (image-input/extract-images "lite a cool robot" {:raw-event {:attachments ()}}) => {:prompt "lite a cool robot" :image-urls nil}
        (gemini/generate-video "a cool robot" nil "veo-3.1-generate-preview" 8 "http://example.com/input.mp4") => :video-bytes
        (store-image! :video-bytes) => "img123"
        (gemini/yetibot-base-url) => "http://localhost:3000"
        (chat/send-msg "<@user123>: http://localhost:3000/generated-images/img123.mp4") => anything))))
