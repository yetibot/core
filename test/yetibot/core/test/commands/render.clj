(ns yetibot.core.test.commands.render
  (:require
   yetibot.core.commands.duckling
   yetibot.core.commands.error
   [yetibot.core.hooks :refer [cmd-hook]]
   [midje.sweet :refer [fact => contains has-prefix just]]
   [yetibot.core.midje :refer [value data error]]
   [yetibot.core.commands.render :refer :all]
   [yetibot.core.util.command-info :refer [command-execution-info]]))

(def execution-opts {:run-command? true
                     :data {:adj "lazy" :noun "water buffalo"}})

(fact
 "a simple template works"
 (:result
  (command-execution-info "render the {{adj}} brown {{noun}}" execution-opts))
 => (value "the lazy brown water buffalo"))

(fact
 "A template operating over a sequential produces a sequential"
 (:result (command-execution-info "render item {{.}}"
                                  (assoc execution-opts
                                         :data [1 2]
                                         :raw [1 2])))
 => (value ["item 1" "item 2"]))

(fact
 "An invalid template that throws an error"
 (:result (command-execution-info
           "render the {{adj|lol}} brown {{noun}}"
           execution-opts))
 (error  "No filter defined with the name 'lol'"))

(fact
 "Some cool selmer filters"
 (:result (command-execution-info
           "render the {{adj|upper}} brown {{noun|capitalize}}"
           execution-opts))
 => (value "the LAZY brown Water buffalo"))

(fact
 "render can access yetibot commands as tags"
 (:result
  (command-execution-info
   (str
    "render the {{adj|yetibot:letters|yetibot:reverse|yetibot:unletters}}"
    " brown {{noun|capitalize}}")
   execution-opts))
 => (value "the yzal brown Water buffalo"))


;; stub a weather command
(def weather-stub (constantly
                   {:result/value ["Seattle, WA (US)"
                                   "66.0°F - Overcast Clouds"
                                   "Feels like 65.8°F"
                                   "Winds 1.3 mph N"]
                    :result/data {:station "E8003",
                                  :rh 74,
                                  :sunset "04:07",
                                  :pres 1008.5,
                                  :timezone "America/Los_Angeles",
                                  :temp 18.9,
                                  :wind_spd 2.06,
                                  :aqi 38,
                                  :ghi 681.33,
                                  :dewpt 14.2,
                                  :h_angle -33.8,
                                  :vis 5,
                                  :sunrise "12:21",
                                  :solar_rad 681.3,
                                  :city_name "Seattle",
                                  :app_temp 18.8,
                                  :ts 1562691060,
                                  :country_code "US",
                                  :snow 0,
                                  :wind_cdir_full "north",
                                  :pod "d",
                                  :dni 841.01,
                                  :dhi 106.4,
                                  :wind_cdir "N",
                                  :lon -122.33207,
                                  :last_ob_time "2019-07-09T16:51:00",
                                  :datetime "2019-07-09:17",
                                  :lat 47.60621,
                                  :weather {:icon "c04d"
                                            :code "804"
                                            :description "Overcast clouds"}

                                  :clouds 0,
                                  :ob_time "2019-07-09 16:51",
                                  :slp 1014.7,
                                  :precip 0,
                                  :wind_dir 360,
                                  :state_code "WA",
                                  :uv 7.29162,
                                  :elev_angle 43.76}}))
(cmd-hook #"weather"
          _ weather-stub)


(fact
 "render a more complex example utilizing data"
 (:result
  (command-execution-info
   "render Score {{score}} - {{zip|yetibot-data:weather|get:weather|get:description}}"
   (assoc execution-opts
          :data [{:score 2 :zip 98144}
                 {:score 1 :zip 98144}])))
 => (value ["Score 2 - Overcast clouds" "Score 1 - Overcast clouds"]))

(fact
 "errors are propagated in yetibot selmer filters"
 (:result
  (command-execution-info
   "render Score {{score}} - {{zip|yetibot:error}}"
   (assoc execution-opts
          :data [{:score 2 :zip 98144}
                 {:score 1 :zip 98144}])))
 => (value ["Score 2 - Error: 98144" "Score 1 - Error: 98144"]))
;; "render {{adj|yetibot:letters|yetibot:flatten|yetibot:\"render you said \\{\\{.\\}\\}\"}}"
