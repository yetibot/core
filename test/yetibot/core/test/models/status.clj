(ns yetibot.core.test.models.status
  (:require [yetibot.core.models.status :as sts]
            [yetibot.core.models.users :refer [get-user]]
            [clj-time
             [core :refer [ago hours]]
             [coerce :refer [to-sql-time]]]
            [midje.sweet :refer [=> fact facts provided anything
                                 every-checker contains]]))

(facts
 "about models.status"
 (fact
  "it creates a coll of strings that contain '<username> <timestamp> <statys>' status info"
  (let [name "zero"
        status "hello world"
        sts {:user-id "0"
             :status status
             :created-at (to-sql-time (-> 8 hours ago))}]
    (first (sts/format-sts [sts])) => (every-checker
                                       (contains status)
                                       (contains name))
    (provided (get-user anything (:user-id sts)) => {:name name}))))