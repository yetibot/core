(ns yetibot.core.test.util.image-input
  (:require
   [midje.sweet :refer [fact facts =>]]
   [yetibot.core.util.image-input :as img-input]))

(facts
  "about extracting user mentions with custom avatar mapping"
  (fact
    "a regular user mention extracts their discord avatar"
    (img-input/extract-images
      "<@123456789>"
      {:raw-event {:mentions [{:id "123456789" :avatar "avatar123" :username "alice"}]}})
    => {:prompt "@alice" :image-urls ["https://cdn.discordapp.com/avatars/123456789/avatar123.png?size=256"]})

  (fact
    "a hardcoded user mention extracts the custom mario death meme avatar"
    (img-input/extract-images
      "<@269292446041636866>"
      {:raw-event {:mentions [{:id "269292446041636866" :avatar "someavatar" :username "bob"}]}})
    => {:prompt "@bob" :image-urls ["https://i.imgflip.com/4/9omh8s.jpg"]}))
