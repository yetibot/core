(ns yetibot.core.test.commands.image-test
  (:require [clojure.string :as string]
            [midje.sweet :refer [facts fact => contains]]
            [yetibot.core.commands.banana]
            [yetibot.core.commands.grokimage :as grokimage]
            [yetibot.core.commands.help :as command-help]
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

(facts "image aliases return image help instead of fuzzy-matching AI"
  (let [combined "grokimage|grokimg|grok-image|gimg|gi"
        image-doc (:doc (meta #'grokimage/grokimage-cmd))
        topics (assoc (select-keys (help/get-docs) ["grokimage" combined])
                      "ai" #{"ai <prompt> # ask an AI provider"})]
    (with-redefs [help/docs (atom topics)
                  help/alias-docs (atom {})]
      (doseq [command ["grokimage" "grokimg" "grok-image" "gimg" "gi"]]
        (fact "every image command name returns the image documentation"
          (command-help/help-for-topic {:args command}) => [image-doc])
        (let [[_ subcommands] (hooks/find-sub-cmds command)]
          (fact "image aliases still execute the image command"
            (second (hooks/match-sub-cmds "space kitty" subcommands))
            => #'grokimage/grokimage-cmd)))
      (fact "the topic listing uses the canonical name, not a regex"
        (command-help/help-topics nil) => (contains "`grokimage`")
        (.contains (command-help/help-topics nil) combined) => false)
      (fact "help all still includes the image documentation exactly once"
        (count (filter #{image-doc}
                       (string/split-lines
                         (command-help/help-all-cmd nil)))) => 1)
      (fact "exact runtime-alias docs take precedence over command aliases"
        (reset! help/alias-docs {"gi" #{"custom gi alias"}})
        (command-help/help-for-topic {:args "gi"})
        => ["custom gi alias"]))))
