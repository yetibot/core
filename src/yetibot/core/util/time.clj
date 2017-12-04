(ns yetibot.core.util.time
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clj-time
     [coerce :refer [from-date]]
     [format :refer [formatter unparse]]
     [local :refer [format-local-time]]
     [core :refer [day year month
                   to-time-zone after?
                   default-time-zone now time-zone-for-id date-time utc
                   ago hours days weeks years months]]]))


(def time-zone (time-zone-for-id "America/Los_Angeles"))

(def short-time (formatter "hh:mm aa MM/dd" time-zone))

(defn format-time [dt] (unparse short-time dt))

(defn after-or-equal? [d1 d2] (or (= d1 d2) (after? d1 d2)))
