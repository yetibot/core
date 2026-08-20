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
    => {:prompt "(Image 1 is @alice) @alice"
        :image-urls ["https://cdn.discordapp.com/avatars/123456789/avatar123.png?size=256"]})

  (fact
    "a hardcoded user mention extracts the custom mario death meme avatar"
    (img-input/extract-images
      "<@269292446041636866>"
      {:raw-event {:mentions [{:id "269292446041636866" :avatar "someavatar" :username "bob"}]}})
    => {:prompt "(Image 1 is @bob) @bob"
        :image-urls ["https://i.imgflip.com/4/9omh8s.jpg"]})

  (fact
    "user mentions are extracted and sorted in typing order, not snowflake order"
    (img-input/extract-images
      "draw <@333333333> and <@111111111>"
      {:raw-event {:mentions [{:id "111111111" :avatar "av1" :username "alice"}
                             {:id "333333333" :avatar "av3" :username "charlie"}]}})
    => {:prompt "(Image 1 is @charlie. Image 2 is @alice) draw @charlie and @alice"
        :image-urls ["https://cdn.discordapp.com/avatars/333333333/av3.png?size=256"
                     "https://cdn.discordapp.com/avatars/111111111/av1.png?size=256"]})

  (fact
    "extracts speaking user's avatar when they say 'me' without explicit self-mention"
    (img-input/extract-images
      "draw me as a wizard"
      {:raw-event {:author {:id "222222222" :avatar "av2" :username "bob"}}})
    => {:prompt "(Image 1 is @bob) draw @bob as a wizard"
        :image-urls ["https://cdn.discordapp.com/avatars/222222222/av2.png?size=256"]})

  (fact
    "extracting speaking user's avatar is case-insensitive for 'me'"
    (img-input/extract-images
      "draw ME flying"
      {:raw-event {:author {:id "222222222" :avatar "av2" :username "bob"}}})
    => {:prompt "(Image 1 is @bob) draw @bob flying"
        :image-urls ["https://cdn.discordapp.com/avatars/222222222/av2.png?size=256"]})

  (fact
    "extracting speaking user's avatar respects word boundaries for 'me'"
    (img-input/extract-images
      "some awesome meme"
      {:raw-event {:author {:id "222222222" :avatar "av2" :username "bob"}}})
    => {:prompt "some awesome meme"
        :image-urls []})

  (fact
    "extracts both 'me' and explicit mentions in the correct typing order"
    (img-input/extract-images
      "draw me and <@111111111>"
      {:raw-event {:author {:id "222222222" :avatar "av2" :username "bob"}
                   :mentions [{:id "111111111" :avatar "av1" :username "alice"}]}})
    => {:prompt "(Image 1 is @bob. Image 2 is @alice) draw @bob and @alice"
        :image-urls ["https://cdn.discordapp.com/avatars/222222222/av2.png?size=256"
                     "https://cdn.discordapp.com/avatars/111111111/av1.png?size=256"]})

  (fact
    "a user without an avatar gets the default discord avatar URL"
    (img-input/extract-images
      "<@123456789>"
      {:raw-event {:mentions [{:id "123456789" :username "alice"}]}})
    => {:prompt "(Image 1 is @alice) @alice"
        :image-urls ["https://cdn.discordapp.com/embed/avatars/5.png"]})

  (fact
    "a single inline image URL is extracted and mapped to Image 1"
    (img-input/extract-images
      "extremely jacked benching https://i.imgflip.com/zuckerburg.jpg for reps"
      {})
    => {:prompt "extremely jacked benching Image 1 for reps"
        :image-urls ["https://i.imgflip.com/zuckerburg.jpg"]})

  (fact
    "multiple inline image URLs are extracted and mapped sequentially"
    (img-input/extract-images
      "compare https://example.com/one.png and https://example.com/two.jpg side by side"
      {})
    => {:prompt "compare Image 1 and Image 2 side by side"
        :image-urls ["https://example.com/one.png"
                     "https://example.com/two.jpg"]})

  (fact
    "a mention and an inline URL are both extracted and mapped sequentially in prompt and urls list"
    (img-input/extract-images
      "extremely jacked <@123456789> benching https://i.imgflip.com/zuckerburg.jpg for reps"
      {:raw-event {:mentions [{:id "123456789" :avatar "avatar123" :username "alice"}]}})
    => {:prompt "(Image 1 is @alice) extremely jacked @alice benching Image 2 for reps"
        :image-urls ["https://cdn.discordapp.com/avatars/123456789/avatar123.png?size=256"
                     "https://i.imgflip.com/zuckerburg.jpg"]}))
