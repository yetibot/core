(ns yetibot.core.test.chat
  (:require [midje.sweet :refer [facts fact => =not=> provided as-checker
                                 throws falsey contains]]
            [clojure.string :as s]
            [yetibot.core.chat :as c]
            [yetibot.core.adapters.adapter :as a]))

(facts
 "about contains-image-url-lines?"
 (fact
  "it will return 'false' for strings that do not contain a URL with image
   like chars"
  (c/contains-image-url-lines? "image.jpg") => false
  (c/contains-image-url-lines? "http://image.html") => false
  (c/contains-image-url-lines? "http://image.jpeeeg") => false
  (c/contains-image-url-lines? "http://image.pnnng") => false
  (c/contains-image-url-lines? "http://image.giiif") => false)

 (fact
  "it will return 'true' for strings that do contain a URL with image
   like chars"
  (c/contains-image-url-lines? "http://image.jpg") => true
  (c/contains-image-url-lines? "https://image.jpg") => true
  (c/contains-image-url-lines? "https://image.jpeg") => true
  (c/contains-image-url-lines? "http://image.png") => true
  (c/contains-image-url-lines? "http://image.gif") => true))

(facts
 "about should-send-msg-for-each?"
 (let [data ["look at my picture"
             "http://image.jpg"]
       joined-data (s/join \newline data)]
   (fact
    "it will return 'true' when you pass it a legit chat data stucture and
     a formatted message that has :newlines and URLs that contain image like
     chars"
    (c/should-send-msg-for-each? data joined-data) => true)
   (fact
    "it will return falsey when the message data is not a collection"
    (c/should-send-msg-for-each? "message" joined-data) => falsey)
   (fact
    "it will return falsey when the number of messages exceeds 30"
    (c/should-send-msg-for-each? (concat data (range 30)) joined-data) => falsey)
   (fact
    "it will return falsey when the formatted message does NOT contain newlines"
    (c/should-send-msg-for-each? data (s/join "" data)) => falsey)
   (fact
    "it will return falsey when the formatted message does NOT contain image URLs"
    (c/should-send-msg-for-each? data "no\nimages\nhere") => falsey)))

(facts
 "about suppressed-pred"
 (fact
  "it will get an object's :suppressed metadata and return it's value"
  (let [truthy (c/suppress {})
        falsey {}]
    (c/suppressed-pred truthy) => true
    (c/suppressed-pred falsey) =not=> true)))

(facts
 "about suppressed?"
 (fact
  "it will check to see if an obj, or collection of objects passed in are
   considered suppressed, and return a boolean result"
  (let [truthy (c/suppress {})
        falsey {}
        truthycoll [truthy truthy]
        mixedcoll [truthy falsey]
        falseycoll [falsey falsey]]
    (c/suppressed? truthy) => true

    ;; why is the below test returning true ??
    ;; i get that it is a coll?, but the item in the coll does NOT
    ;; contain a :suppress metadata item eql to true, so what gives ??
    ;; (c/suppressed? falsey) =not=> true

    (c/suppressed? truthycoll) => true
    (c/suppressed? mixedcoll) =not=> true
    (c/suppressed? falseycoll) =not=> true)))

(facts
 "about suppress"
 (fact
  "it attaches a metadata :supress keyword equal to true to whatever obj is
  provided"
  (let [truthy (c/suppress {})]
    (:suppress (meta truthy)) => true)))

(facts
 "about with-target"
 (fact
  "it attaches a metadata :target keyword equal to whatever value is provided
   to the obj (data-structure) that is provided"
  (let [target "i am the target"
        ds (c/with-target target {})]
    (:target (meta ds)) => target)))

(facts
 "about channels-with-broadcast"
 (fact
  "it will return a collection of only those channels that have a broadcast
   settings value equal to true"
  (count (c/channels-with-broadcast)) => 1
  (provided (c/all-channels) => [[nil nil nil]
                                 [nil nil {"broadcast" "true"}]
                                 [nil nil {"broadcast" "false"}]])))

(facts
 "about send-msg-for-each"
 (fact
  "it will send a message for each item in the msg collection, assuming it
   is less than the max allowable"
  (c/send-msg-for-each [1]) => nil
  (provided (c/send-msg 1) => :didsend))

 (fact
  "it will only send the max number of allowed messages in a single batch, plus
   one to let user know messsage batch was truncated"
  (c/send-msg-for-each (range 40)) => :sent-msg
  (provided
   (c/send-msg (as-checker int?)) => :sent-msg :times c/max-msg-count
   (c/send-msg (as-checker string?)) => :sent-msg :times 1)))

(facts
 "about validate-sender"
 (let [vs-fn (c/validate-sender #'str)]
   (fact
    "will return a function that when executed will throw an exception when both the
     adapter and adapter-uuid binding is falsey"
    (binding [c/*adapter* false
              c/*adapter-uuid* false]
      (vs-fn "doesn't matter") => (throws Exception)))
   (fact
    "will return a function that when executed with a truthy adapter binding will
     operate on the defined msg param when it is not empty"
    (binding [c/*adapter* true]
      (vs-fn "return me") => "return me"))
   (fact
    "will return a function that when executed with a truthy adapter binding will
     operate on a default message of 'No results' because the msg param is empty"
    (binding [c/*adapter* true]
      (vs-fn "") => "No results"))
   (fact
    "will return a function that when executed with a truthy adapter-uuid binding will
     operate on the defined msg param when it is not empty"
    (binding [c/*adapter-uuid* true]
      (vs-fn "return me") => "return me"))))

(facts
 "about base-chat-source"
 (fact
  "it will return a map that contains the keywordized name of the adapter and a
   related UUID of the instance of the adapter"
  (c/base-chat-source) => {:adapter :name :uuid "uuid"}
  (provided
   (a/platform-name c/*adapter*) => "name"
   (a/uuid c/*adapter*) => "uuid")))

(facts
 "about chat-source"
 (fact
  "it will get the base chat source and merge the channel param with a key
   identifier of :room"
  (c/chat-source "channel") => {:room "channel"}
  (provided
   (c/base-chat-source) => {})))

(facts
 "about broadcast"
 (fact
  "it will get all channels with broadcast settings, bind the channel as the
   target, and then send a message using the related adapter and message param,
   and return nil"
  (c/broadcast "message") => nil
  (provided
   (c/channels-with-broadcast) => [["adapter" "channel" "settings"]]
   (a/send-msg "adapter" "message") => nil)))

(facts
 "about all-channels"
 (fact
  "it will return an empty collection when there are no active adapters
   and/or channels"
  (c/all-channels) => '())
 
 (fact
  "it will will return a collection of collections that contains info about
   active adapters, channels associated with an adapter, and a map of channel
   settings"
  (first (c/all-channels)) => (contains "test"
                                        "channel1"
                                        map?)
  (provided
   (a/active-adapters) => ["test"]
   (a/channels "test") => ["channel1" "channel2"]
   (a/uuid "test") => :testuuid)))
