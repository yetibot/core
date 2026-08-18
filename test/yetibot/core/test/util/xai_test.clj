(ns yetibot.core.test.util.xai-test
  (:require [midje.sweet :refer [facts fact => contains provided throws]]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [yetibot.core.util.xai :as xai]))

(facts "about xai configured?"
       (fact "it returns true if config has a key"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/configured?)) => true)
       (fact "it returns false if config does not have a key"
             (with-redefs [xai/config {}]
               (xai/configured?)) => false))

(facts "about xai cost-per-image"
       (fact "it returns default if not overridden in config"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/cost-per-image)) => 0.04)
       (fact "it returns the configured number value"
             (with-redefs [xai/config {:key "secret-key" :cost-per-image 0.055}]
               (xai/cost-per-image)) => 0.055)
       (fact "it returns the parsed string value from config"
             (with-redefs [xai/config {:key "secret-key" :cost-per-image "0.065"}]
               (xai/cost-per-image)) => 0.065))

(facts "about xai cost-per-prompt"
       (fact "it returns default if not overridden in config"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/cost-per-prompt)) => 0.01)
       (fact "it returns the configured number value"
             (with-redefs [xai/config {:key "secret-key" :cost-per-prompt 0.025}]
               (xai/cost-per-prompt)) => 0.025)
       (fact "it returns the parsed string value from config"
             (with-redefs [xai/config {:key "secret-key" :cost-per-prompt "0.035"}]
               (xai/cost-per-prompt)) => 0.035))

(facts "about xai generate-image"
       (fact "it generates an image successfully when API returns valid data"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/generate-image "a green banana") => {:data "b64data123" :mime-type "image/jpeg"}
               (provided
                 (client/post "https://api.x.ai/v1/images/generations"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body string?}))
                 => {:status 200
                     :body {:data [{:b64_json "b64data123"}]}})))

       (fact "it throws an error when API returns non-200 status"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/generate-image "a green banana") => (throws Exception #"xAI API error: Rate limit exceeded")
               (provided
                 (client/post "https://api.x.ai/v1/images/generations"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body string?}))
                 => {:status 429
                     :body {:error {:message "Rate limit exceeded"}}})))

       (fact "it throws an error when API response is missing image data"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/generate-image "a green banana") => (throws Exception #"No image data returned from xAI API")
               (provided
                 (client/post "https://api.x.ai/v1/images/generations"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body string?}))
                 => {:status 200
                     :body {:data []}}))))
