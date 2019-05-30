(ns yetibot.core.adapters.adapter
  "Defines the Adapater protocol for chat adapters and keeps track of instances.
   All chat sources must implement it."
  (:require
    [taoensso.timbre :as log :refer [info debug warn error]]))

(defprotocol Adapter

  ;; TODO implement this in IRC and Slack adapters, then call it from the
  ;; respective functions in each adapter that handle incoming messages.
  (resolve-users
    [_ event]
    "Given some kind of event, figure out if users are involved and resolve them
     (where resolve means look them up in the DB, creating them on demand if
     they don't already exist).

     Users are accreted lazily over time in Yetibot as they are:

     - observed because they authored a message
     - mentioned in another user's message
     - found to be in a channel after a user asked for the members of a channel
     - possibly other ways we haven't thought about yet

     Note: there is no standard notion of events shared between adapters and
     there doesn't necessarily need to be, since the event is handled completely
     internally by each adapter.

     Types of events and corresponding users could include:

     1. Incoming message
       - An incoming message has an author
       - A message may mention a user or users. In IRC this could rely on a
         convention of @username style mentions. In Slack, it's explicit since
         Slack encodes users.

     2. Channel join or leave
       - This is a more eager resolution of users - probably not worth doing in
         the initial pass, but listed here as an example of the kind of thing we
         *could* do.

     For each detected user call `resolve-user!` to ensure it's persisted and
     canonicalized.

     Returns a map of {id user}.
     ")

  ;; TODO implement
  (resolve-user! [_ adapter-user]
    "Takes a user in the shape that a specific adapter provides and
     canonicalizes and persists (if not already).

     See yetibot.core.models.users/create-user for the existing attempt to do
     this (in memory representation only - no persistence).

     Returns the canonicalized user.")

  ;; TODO implement in all adapters
  (users [_ channel]
    "Given a channel, figure out which users are in the channel and resolve all
     of them via `resolve-user!`.

     Returns a map of {id user}")

  (uuid [_] "A UUID that represents an instance, represented in config by the
        :name key")

  (platform-name [_] "String describing the chat platform this adapter supports.")

  (channels [_] "A vector of channels yetibot is in")

  (send-paste [_ msg] "Multi-line strings meant to be formatted as code")

  (send-msg [_ msg] "Single message to post")

  (join [_ channel]
    "join channel - may not be supported by all adapters, e.g. Slack. In this
         case the adapter should return instructions for its method of joining
         (e.g. /invite in Slack).")

  (leave [_ channel] "leave channel - may not be supported - should give instructions
                      just like join.")

  (chat-source [_ channel] "Define a chat-source map specific to this adapter")

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
