(ns yetibot.core.commands.date
  (:require [duckling.core :as duckling]
            [yetibot.core.hooks :refer [cmd-hook]]
            [schema.core :as sch]
            [yetibot.core.config :refer [get-config]]))

(defn languages []
  (or (:value (get-config [sch/Str] [:duckling :languages]))
      ["en"]))

(defonce load-duckling
  (duckling/load! {:languages (languages)}))

(comment
  ;; returns the next 3 saturdays
  (duckling/parse :en$core "saturday afternoon" [:time])
  ;; a single date
  (duckling/parse :en$core "the day after tomorrow")
  ;; a time, down to the minute
  (duckling/parse :en$core "3 hours ago")
  ;; a holiday
  (duckling/parse :en$core "next thanksgiving")
  (-> (duckling/parse :en$core "next thanksgiving")
      first :value :values first :value)
  ;; doesn't make sense returns empty seq
  (seq (duckling/parse :en$core "do you even" [:time]))
  )

(defn date-cmd
  "date # date <natural language date expression # parse a date using Duckling

   Try things like:

   date 2 hours ago
   date next thanksgiving
   date saturday

   This can be used in conjunction with commands that take dates, like:

   !history --since `date a week ago`

   Check out https://duckling.wit.ai/ for more information on Duckling."
  [{match :match}]
  (if-let [result (seq (duckling/parse :en$core match [:time]))]
    {:result/data result
     :result/value (or
                    ;; single value
                    (-> result first :value :value)
                    ;; or get the first when there are multiples
                    (-> result first :value :values first :value))}
    {:result/error (str match " doesn't look like a date")}
    ))

(cmd-hook #"date"
  _ date-cmd
  )
