(ns yetibot.core.models.status
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.db.status :refer :all]
    [clj-time
     [coerce :refer [from-date to-sql-time]]
     [format :refer [formatter unparse]]
     [core :refer [day year month
                   to-time-zone after?
                   default-time-zone now time-zone-for-id date-time utc
                   ago hours days weeks years months]]]
    [yetibot.core.models.users :refer [get-user]]
    [yetibot.core.util.time :as t]
    [yetibot.core.interpreter]))

;;;; write

(defn add-status [{:keys [chat-source-adapter] :as status}]
  (create (assoc status
                 :chat-source-adapter (str chat-source-adapter))))

;;;; helpers

(def ^:private prepare-data
  "Turn user-ids into actual user maps and convert java dates to joda"
  (partial map
           (fn [{:keys [user-id status created-at]}]
             [(get-user yetibot.core.interpreter/*chat-source* user-id)
              status
              (from-date created-at)])))

(def ^:private sts-to-strings
  "Format statuses collection as a collection of string"
  (partial map
           (fn [[user st date]]
             (format "%s at %s: %s"
                     (:name user)
                     (t/format-time date)
                     st))))

(defn format-sts
  "Transform statuses collection into a normalized collection of formatted strings"
  [ss]
  (-> ss
      prepare-data
      sts-to-strings))

;;;; read

(defn status-since
  "Retrieve statuses after or equal to a given joda timestamp"
  [{:keys [adapter room] :as chat-source} ts]
  (info "show status for" chat-source "since" (to-sql-time ts))
  (query
    {:where/clause (str "chat_source_adapter=? AND chat_source_room=?"
                        " AND created_at >= ?")
     :where/args  [(pr-str adapter) room (to-sql-time ts)]}))
