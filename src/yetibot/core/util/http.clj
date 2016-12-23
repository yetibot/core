(ns yetibot.core.util.http
  (:require
    [yetibot.core.util.format :refer [limit-and-trim-string-lines]]
    ;; [http.async.client :as client]
    [clj-http.client :as client]
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :as s]
    [clojure.xml :as xml]
    [clojure.data.json :as json])
  (:import [java.net URL URLEncoder]))


; synchronous api call helpers

;; (defmacro with-client [uri verb-fn auth & body]
;;   `(with-open [~'client (client/create-client)]
;;      (let [~'response (~verb-fn ~'client ~uri :auth ~auth)]
;;        ~@body)))

(defn fetch
  ([uri] (fetch uri {:user "" :password ""})) ; default empty user/pass
  ([uri auth] (fetch uri client/get auth)) ; default GET
  ([uri verb-fn auth]
   (:body (verb-fn
            uri
            {:basic-auth [(:user auth) (:password auth)]}))))

(defn get-json [& args]
  (let [raw (apply fetch args)]
    (try
      (json/read-json raw)
      (catch Exception e
        (error "Exception trying to fetch json" args)
        (let [limited-raw (str (limit-and-trim-string-lines 3 raw) "...")]
          (throw (Exception. (str "Unable to parse JSON from response:" limited-raw))))))))

(defn fetch-xml [& args]
  (let [raw (apply fetch args)]
    (try
      (xml/parse raw)
      (catch Exception e
        (error "Exception trying to fetch xml" args)
        (let [limited-raw (str (limit-and-trim-string-lines 3 raw) "...")]
          (throw (Exception. (str "Unable to parse XML from response:" limited-raw))))))))

(defn encode [s]
  (URLEncoder/encode (str s) "UTF-8"))

(defn html-decode [s]
  (-> (str s)
      (s/replace "&amp;" "&")
      (s/replace "&lt;" "<")
      (s/replace "&gt;" ">")
      (s/replace "&quot;" "\"")))

(defn ensure-img-suffix
  "Add an image url suffix if not already present."
  {:test #(assert (= "foo?.gif" (ensure-img-suffix "foo")))}
  [url]
  (if (re-find #"\.(jpg|png|gif)$" url)
    url
    (format "%s?.gif" url)))

; query string helper
(defn map-to-query-string [m]
  (s/join "&" (map (fn [[k v]] (format "%s=%s"
                                       (encode (name k)) (encode v))) m)))
