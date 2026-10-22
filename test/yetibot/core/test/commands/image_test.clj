(ns yetibot.core.test.commands.image-test
  (:require [midje.sweet :refer [facts fact =>]]
            [yetibot.core.commands.banana]
            [yetibot.core.commands.grokimage]
            [yetibot.core.commands.veo]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.loader :as loader]
            [yetibot.core.models.help :as help]))

(facts "the retired image command is not available"
  (fact "the command loader cannot discover the removed namespace"
    (loader/find-namespaces #"yetibot\.core\.commands\.image") => empty?)
  (fact "neither img nor its image alias has a command handler"
    (hooks/find-sub-cmds "img") => nil
    (hooks/find-sub-cmds "image") => nil)
  (fact "the removed command has no help entry"
    (help/get-docs-for "image|img") => nil))

(facts "other image and video commands stay available"
  (doseq [command ["grokimage" "grokimg" "grok-image" "gimg" "gi"
                   "banana" "veo" "gigaveo"]]
    (fact "the command still has a registered handler"
      (hooks/find-sub-cmds command) => some?)))
