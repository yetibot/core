(ns yetibot.core.commands.duckling
  (:require [duckling.core :as duckling]
            [yetibot.core.hooks :refer [cmd-hook]]
            [schema.core :as sch]
            [yetibot.core.config :refer [get-config]]))

(defn languages []
  (or (:value (get-config [sch/Str] [:duckling :languages]))
      ["en"]))

(def parse (partial duckling/parse :en$core))

(defonce load-duckling
  (duckling/load! {:languages (languages)}))

(comment
  ;; returns the next 3 saturdays
  (parse "saturday afternoon" [:time])
  ;; a single date
  (parse "the day after tomorrow")
  ;; a time, down to the minute
  (parse "3 hours ago")
  ;; a holiday
  (parse "next thanksgiving")
  (-> (parse "next thanksgiving")
      first :value :values first :value)
  ;; doesn't make sense returns empty seq
  (seq (parse "do you even" [:time]))
  (-> (parse "2 dollars") first))

(def dims {:amount-of-money "Amount of money"
           :cycle "Cycle"
           :distance "Distance"
           :duration "Duration"
           :email "Email"
           :leven-product "Leven product"
           :leven-unit "Leven unit"
           :number "Number"
           :ordinal "Ordinal"
           :phone-number "Phone number"
           :quantity "Quantity"
           :temperature "Temperature"
           :time "Time"
           :timezone "Timezone"
           :unit "Unit"
           :unit-of-duration "Unit of duration"
           :url "URL"
           :volume "Volume"})

(defn format-duckling-result
  [{:keys [dim type value unit grain]}]
  (format "%s: %s" (get dims dim) (:value value))
  )

(comment
  (map format-duckling-result (parse "yesterday"))
  (map format-duckling-result (parse "what costs 2 dollars")))

(defn duckling-cmd
  "duckling # <natural language date expression> # parse an expression using Duckling

   Try things like:

   duckling thirty two celsius
   duckling 2 miles

   See https://duckling.wit.ai/ for more information on Duckling."
  [{match :match}]
  (if-let [result (seq (parse match))]
    {:result/data result
     :result/value (map format-duckling-result result)}))

(cmd-hook #"duckling"
  _ duckling-cmd)

;; date-cmd is a similar to duckling-cmd except it specifically parses for :time
;; dimensions and errors if nothing found

(defn date-cmd
  "date # date <natural language date expression # parse a date using Duckling

   Try things like:

   date 2 hours ago
   date next thanksgiving
   date saturday

   This can be used in conjunction with commands that take dates, like:

   !history --since `date a week ago`

   See https://duckling.wit.ai/ for more information on Duckling."
  [{match :match}]
  (if-let [result (seq (parse match [:time]))]
    {:result/data result
     :result/value (or
                    ;; single value
                    (-> result first :value :value)
                    ;; or get the first when there are multiples
                    (-> result first :value :values first :value))}
    {:result/error (str match " doesn't look like a date")}
    ))

(cmd-hook #"date"
  _ date-cmd)
