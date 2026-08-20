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

(facts "about xai generate-image"
       (fact "it generates an image successfully when API returns valid data"
             (with-redefs [xai/config {:key "secret-key"}
                           xai/optimize-image-prompt identity]
               (xai/generate-image "a green banana") => {:data "b64data123" :mime-type "image/jpeg"}
               (provided
                 (client/post "https://api.x.ai/v1/images/generations"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body (fn [b]
                                                 (let [parsed (json/read-str b)]
                                                   (and (= "low" (get parsed "quality"))
                                                        (= "grok-imagine-image-2.0" (get parsed "model"))
                                                        (= "a green banana" (get parsed "prompt")))))}))
                 => {:status 200
                     :body {:data [{:b64_json "b64data123"}]}})))

       (fact "it edits an image via /v1/images/edits when image-urls are provided"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/generate-image "make it cool" ["https://example.com/img.png"]) => {:data "editb64data" :mime-type "image/jpeg"}
               (provided
                 (client/post "https://api.x.ai/v1/images/edits"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body (fn [b]
                                                 (let [parsed (json/read-str b)]
                                                   (and (= "low" (get parsed "quality"))
                                                        (= "grok-imagine-image-2.0" (get parsed "model"))
                                                        (= "make it cool" (get parsed "prompt"))
                                                        (= {"url" "https://example.com/img.png" "type" "image_url"} (get parsed "image")))))}))
                 => {:status 200
                     :body {:data [{:b64_json "editb64data"}]}})))

       (fact "it edits multiple images via /v1/images/edits when multiple image-urls are provided"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/generate-image "combine these" ["https://example.com/img1.png" "https://example.com/img2.png"]) => {:data "editb64data" :mime-type "image/jpeg"}
               (provided
                 (client/post "https://api.x.ai/v1/images/edits"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body (fn [b]
                                                 (let [parsed (json/read-str b)]
                                                   (and (= "low" (get parsed "quality"))
                                                        (= "grok-imagine-image-2.0" (get parsed "model"))
                                                        (= "combine these" (get parsed "prompt"))
                                                        (= [{"url" "https://example.com/img1.png" "type" "image_url"}
                                                            {"url" "https://example.com/img2.png" "type" "image_url"}] (get parsed "images")))))}))
                 => {:status 200
                     :body {:data [{:b64_json "editb64data"}]}})))

       (fact "it edits all 4+ images without truncating them via /v1/images/edits"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/generate-image "combine these" ["https://example.com/img1.png"
                                                    "https://example.com/img2.png"
                                                    "https://example.com/img3.png"
                                                    "https://example.com/img4.png"]) => {:data "editb64data" :mime-type "image/jpeg"}
               (provided
                 (client/post "https://api.x.ai/v1/images/edits"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body (fn [b]
                                                 (let [parsed (json/read-str b)]
                                                   (and (= "low" (get parsed "quality"))
                                                        (= "grok-imagine-image-2.0" (get parsed "model"))
                                                        (= "combine these" (get parsed "prompt"))
                                                        (= [{"url" "https://example.com/img1.png" "type" "image_url"}
                                                            {"url" "https://example.com/img2.png" "type" "image_url"}
                                                            {"url" "https://example.com/img3.png" "type" "image_url"}
                                                            {"url" "https://example.com/img4.png" "type" "image_url"}] (get parsed "images")))))}))
                 => {:status 200
                     :body {:data [{:b64_json "editb64data"}]}})))

       (fact "it prepends 'Combine these images: ' to the prompt when multiple images are passed and prompt does not have combine/merge"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/generate-image "sketch these" ["https://example.com/img1.png" "https://example.com/img2.png"]) => {:data "editb64data" :mime-type "image/jpeg"}
               (provided
                 (client/post "https://api.x.ai/v1/images/edits"
                              (contains {:body (fn [b]
                                                 (let [parsed (json/read-str b)]
                                                   (= "Combine these images: sketch these" (get parsed "prompt"))))}))
                 => {:status 200
                     :body {:data [{:b64_json "editb64data"}]}})))

       (fact "it defaults blank prompt to 'combine these images beautifully' when multiple images are passed"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/generate-image "" ["https://example.com/img1.png" "https://example.com/img2.png"]) => {:data "editb64data" :mime-type "image/jpeg"}
               (provided
                 (client/post "https://api.x.ai/v1/images/edits"
                              (contains {:body (fn [b]
                                                 (let [parsed (json/read-str b)]
                                                   (= "combine these images beautifully" (get parsed "prompt"))))}))
                 => {:status 200
                     :body {:data [{:b64_json "editb64data"}]}})))

       (fact "it throws an error when API returns non-200 status"
             (with-redefs [xai/config {:key "secret-key"}
                           xai/optimize-image-prompt identity]
               (xai/generate-image "a green banana") => (throws Exception #"xAI API error: Rate limit exceeded")
               (provided
                 (client/post "https://api.x.ai/v1/images/generations"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body string?}))
                 => {:status 429
                     :body {:error {:message "Rate limit exceeded"}}})))

       (fact "it throws an error when API returns flat error message (non-nested)"
             (with-redefs [xai/config {:key "secret-key"}
                           xai/optimize-image-prompt identity]
               (xai/generate-image "a green banana") => (throws Exception #"xAI API error: Argument not supported")
               (provided
                 (client/post "https://api.x.ai/v1/images/generations"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body string?}))
                 => {:status 400
                     :body {:code "invalid_request_error"
                            :error "Argument not supported"}})))

       (fact "it throws an error when API returns flat error message as a raw JSON string"
             (with-redefs [xai/config {:key "secret-key"}
                           xai/optimize-image-prompt identity]
               (xai/generate-image "a green banana") => (throws Exception #"xAI API error: Custom JSON string error")
               (provided
                 (client/post "https://api.x.ai/v1/images/generations"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body string?}))
                 => {:status 400
                     :body "{\"error\": \"Custom JSON string error\"}"})))

       (fact "it falls back to HTTP error status code when API response is unparseable"
             (with-redefs [xai/config {:key "secret-key"}
                           xai/optimize-image-prompt identity]
               (xai/generate-image "a green banana") => (throws Exception #"xAI API error: HTTP error 400")
               (provided
                 (client/post "https://api.x.ai/v1/images/generations"
                     (contains {:headers {"Authorization" "Bearer secret-key"}
                                :content-type :json
                                :as :json
                                :body string?}))
                 => {:status 400
                     :body "Bad Request"})))

       (fact "it throws an error when API response is missing image data"
             (with-redefs [xai/config {:key "secret-key"}
                           xai/optimize-image-prompt identity]
               (xai/generate-image "a green banana") => (throws Exception #"No image data returned from xAI API")
               (provided
                 (client/post "https://api.x.ai/v1/images/generations"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body string?}))
                 => {:status 200
                     :body {:data []}}))))

(facts "about xai generate-text"
       (fact "it generates text and calculates cost based on usage"
             (with-redefs [xai/config {:key "secret-key"}]
               (xai/generate-text "hello") => {:text "grok-response" :cost 0.000080}
               (provided
                 (client/post "https://api.x.ai/v1/chat/completions"
                              (contains {:headers {"Authorization" "Bearer secret-key"}
                                         :content-type :json
                                         :as :json
                                         :body string?}))
                 => {:status 200
                     :body {:choices [{:message {:content "grok-response"}}]
                            :usage {:prompt_tokens 10 :completion_tokens 10}}}))))

(facts "about xai optimize-image-prompt"
       (fact "it returns blank prompt unchanged"
             (xai/optimize-image-prompt "") => "")

       (fact "it optimizes prompt via generate-text when prompt is present"
             (with-redefs [xai/generate-text (fn [p] {:text "optimized visual description" :cost 0.001})]
               (xai/optimize-image-prompt "A slapping B") => "optimized visual description"))

       (fact "it falls back gracefully on generate-text exceptions"
             (with-redefs [xai/generate-text (fn [p] (throw (Exception. "API Error")))]
               (xai/optimize-image-prompt "A slapping B") => "A slapping B"))

       (fact "it falls back when generate-text returns nil"
             (with-redefs [xai/generate-text (fn [p] {:text nil})]
               (xai/optimize-image-prompt "A slapping B") => "A slapping B")))
