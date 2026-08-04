(ns yetibot.core.test.commands.weathermap-test
  (:require [midje.sweet :refer [facts fact => contains provided anything]]
            [yetibot.core.commands.weathermap :as wm]
            [yetibot.core.util.gemini :as gemini]))

(facts "about weathermap-cmd"
       (fact "it returns an error if Gemini is not configured"
             (wm/weathermap-cmd {}) => (contains {:result/error string?})
             (provided (gemini/configured?) => false))

       (fact "it generates a weather map image and returns URL"
             (wm/weathermap-cmd {:chat-source {} :user {:username "test-user"}})
             => (contains {:result/value #"http://localhost:3003/generated-images/img456.png\n\nSent via gemini-3.6-flash-image-preview \| Cost: \$0.039"
                           :result/data {:id "img456"
                                         :prompt "A highly detailed, professional weather map of North America showing temperature heat map, wind movements (using beautiful swirling arrows and streamlines), and air quality index overlays. Use modern weather graphics. The current conditions for reference in some cities are: Edmonton: 15C, Sunnyvale: 25C"
                                         :url "http://localhost:3003/generated-images/img456.png"}})
             (provided
               (gemini/configured?) => true
               (yetibot.core.handler/handle-unparsed-expr {} {:username "test-user"} "utemps") => "Edmonton: 15C, Sunnyvale: 25C"
               (gemini/generate-image "A highly detailed, professional weather map of North America showing temperature heat map, wind movements (using beautiful swirling arrows and streamlines), and air quality index overlays. Use modern weather graphics. The current conditions for reference in some cities are: Edmonton: 15C, Sunnyvale: 25C" anything) => {:data "bytes" :mime-type "image/png"}
               (yetibot.core.webapp.routes.images/store-image! {:data "bytes" :mime-type "image/png"}) => "img456"
               (gemini/yetibot-base-url) => "http://localhost:3003"
               (gemini/gemini-model) => "gemini-3.6-flash-image-preview"
               (gemini/cost-per-image) => 0.039)))
