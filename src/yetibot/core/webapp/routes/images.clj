(ns yetibot.core.webapp.routes.images
  "Serves generated media stored in the in-memory image store."
  (:require [compojure.core :refer [GET defroutes]]
            [ring.util.response :as response])
  (:import [java.util Base64]))

(defonce image-store (atom {}))

(def ^:private max-stored-images 100)

(defn store-image!
  "Store base64-encoded media and return its unique ID.
   Evicts the oldest entry when the store exceeds max-stored-images."
  [{:keys [data mime-type] :as image-data}]
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! image-store
           (fn [store]
             (let [store (assoc store id (assoc image-data :timestamp (System/currentTimeMillis)))]
               (if (> (count store) max-stored-images)
                 (let [oldest-key (->> store
                                       (sort-by (comp :timestamp val))
                                       first
                                       key)]
                   (dissoc store oldest-key))
                 store))))
    id))

(defn- serve-stored
  "Serve stored media by id, falling back to default-mime if none was recorded."
  [id default-mime]
  (if-let [{:keys [data mime-type]} (get @image-store id)]
    (let [bytes (.decode (Base64/getDecoder) ^String data)]
      (-> (response/response (java.io.ByteArrayInputStream. bytes))
          (response/content-type (or mime-type default-mime))
          (response/header "Cache-Control" "public, max-age=3600")))
    (response/not-found "Image not found")))

(defroutes image-routes
  (GET "/generated-images/:id.png" [id] (serve-stored id "image/png"))
  (GET "/generated-images/:id.gif" [id] (serve-stored id "image/gif"))
  (GET "/generated-images/:id.mp4" [id] (serve-stored id "video/mp4")))
