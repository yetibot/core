(ns yetibot.core.adapters.adapter
  "Defines the Adapater protocol for chat adapters and keeps track of instances.
   All chat sources must implement it."
  (:require
    [taoensso.timbre :as log :refer [info debug warn error]]))

(defprotocol Adapter

  (uuid [_] "A UUID that represents an instance, represented in config by the
             :name key")

  (platform-name [_] "String describing the chat platform this adapter supports.")

  (rooms [_] "A vector of rooms yetibot is in")

  (send-paste [_ msg] "Multi-line strings meant to be formatted as code")

  (send-msg [_ msg] "Single message to post")

  (join [_ room]
        "join room - may not be supported by all adapters, e.g. Slack. In this
         case the adapter should return instructions for its method of joining
         (e.g. /invite in Slack).")

  (leave [_ room] "leave room - may not be supported - should give instructions
                   just like join.")

  (chat-source [_ room] "Define a chat-source map specific to this adapter")

  (start [_] "Start the chat adapter")

  (connected? [_] "Check whether the adapter is connected or not")

  (connection-last-active-timestamp
    [_]
    "Return a timestamp for the last time a message or pong or any form of `ack`
     was received by the server")

  (connection-latency [_] "The last known latency for this connection")

  (stop [_] "Stop the chat adapter"))

; Instances of Adapters. If there are multiple configurations for an Adapter
; (e.g. currently Slack can have multiple configs) there will be an instance
; for each one. Key is uuid of config, val is the instance.
(defonce adapters (atom {}))

(defn register-adapter!
  "- adapter is an implmentor of the Adapter protocol
   - config-uuid is a uuid of the config that was used to create the adapter"
  [uuid adapter]
  (swap! adapters assoc uuid adapter)
  (debug "Registered" uuid "-" (pr-str adapter)))

(defn active-adapters [] (vals @adapters))

(comment
  ;; play with adapters here

  (-> (active-adapters)
      first
      (platform-name))

  (-> (active-adapters)
      first
      connected?
      )

  (-> (active-adapters)
      second
      :conn
      deref
      deref
      :ready?
      deref
      )

  (-> (active-adapters)
      first
      (start))

  (-> (active-adapters)
      second
      (connected?))

  (-> (active-adapters)
      second
      (stop))

  (-> (active-adapters)
      second
      (start))

  )
