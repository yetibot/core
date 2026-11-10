(ns yetibot.core.test.webapp.routes.images
  (:require [clojure.string :as str]
            [midje.sweet :refer [fact =>]]
            [yetibot.core.webapp.routes.images :as images])
  (:import [java.util Base64]))

(defn request [extension]
  (images/image-routes {:request-method :get
                       :uri (str "/generated-images/diagram." extension)}))

(fact "diagram downloads retain exact bytes and sandbox active SVG on every alias"
  (let [svg "<svg xmlns='http://www.w3.org/2000/svg'><script>bad()</script></svg>"
        data (.encodeToString (Base64/getEncoder) (.getBytes svg "UTF-8"))]
    (with-redefs [images/image-store (atom {"diagram" {:data data :mime-type "image/svg+xml"}})]
      (doseq [extension ["svg" "png"]]
        (let [response (request extension)]
          (:status response) => 200
          (get-in response [:headers "Content-Type"]) => "image/svg+xml"
          (get-in response [:headers "X-Content-Type-Options"]) => "nosniff"
          (str/includes? (get-in response [:headers "Content-Security-Policy"] "")
                         "sandbox") => true
          (str/starts-with? (get-in response [:headers "Content-Disposition"] "")
                            "attachment;") => true
          (slurp (:body response)) => svg)))))

(fact "PDF and textual diagrams are downloads, not active inline documents"
  (doseq [[extension mime] [["pdf" "application/pdf"] ["txt" "text/plain"]
                           ["utxt" "text/plain"] ["base64" "text/plain"]]]
    (with-redefs [images/image-store
                  (atom {"diagram" {:data "SGVsbG8=" :mime-type mime}})]
      (let [response (request extension)]
        (:status response) => 200
        (slurp (:body response)) => "Hello"
        (get-in response [:headers "Content-Type"]) => mime
        (str/starts-with? (get-in response [:headers "Content-Disposition"] "")
                          "attachment;") => true))))

(fact "ordinary raster media remains inline and unknown artifacts stay missing"
  (with-redefs [images/image-store (atom {"diagram" {:data "SGVsbG8=" :mime-type "image/jpeg"}})]
    (:status (request "jpeg")) => 200
    (get-in (request "jpeg") [:headers "Content-Disposition"]) => nil
    (images/image-routes {:request-method :get :uri "/generated-images/missing.svg"})
    => {:status 404 :headers {} :body "Image not found"}
    (request "html") => nil))
