(ns yetibot.core.webapp.routes.api
  (:require [yetibot.core.interpreter :refer [*chat-source*]]
            [yetibot.core.handler :refer [handle-unparsed-expr]]
            [yetibot.core.chat :refer [*target* *adapter-uuid* chat-data-structure]]
            [taoensso.timbre :refer [info]]
            [clojure.edn :as edn]
            [compojure.core :refer [GET POST defroutes]]))

(defn api [{:keys [chat-source text command token] :as params} req]
  (info "/api called with params:" params)
  (info "/api request:" req)
  (cond
    (empty? chat-source) "chat-source parameter is required!"
    (and (empty? command) (empty? text)) "command or text parameter is required!"
    :else (if-let [chat-source (edn/read-string chat-source)]
            (binding [*chat-source* chat-source
                      *adapter-uuid* (:uuid chat-source)
                      *target* (:room chat-source)]
              (info "chat-source" chat-source)
              (let [user {:username "api"}
                    channel (:room chat-source)
                    res (or text (handle-unparsed-expr chat-source user command))]
                (chat-data-structure res)
                res))
            (str "invalid chat-source:" chat-source))))

(defroutes api-routes
  (GET "/api" [& params :as req] (api params req))
  (POST "/api" [& params :as req] (api params req)))
