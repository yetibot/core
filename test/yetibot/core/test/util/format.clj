(ns yetibot.core.test.util.format
  (:require
   [midje.sweet :refer [=> =not=> contains fact facts]]
   [yetibot.core.util.format :as fmt]))

(def nested-list
  [["meme generator"
    "http://assets.diylol.com/hfs/de6/c7c/061/resized/mi-bok-meme-generator-i-look-both-ways-before-crossing-the-street-at-the-same-time-0dd824.jpg"]
   ["meme maker"
    "http://cdn9.staztic.com/app/a/128/128867/meme-maker-27-0-s-307x512.jpg"]
   ["meme creator"
    "http://img-ipad.lisisoft.com/img/2/9/2974-1-meme-creator-pro-caption-memes.jpg"]])

(facts
 "about format-data-structure"
 (fact
  "the flattened representation should not contain collections"
  (let [[_ flattened] (fmt/format-data-structure nested-list)]
    flattened =not=> (contains coll?))))

(facts
 "about format-n"
 (fact
  "will alter text with params using provided elements"
  (fmt/format-n "foo %1" [2]) => "foo 2")
 (fact
  "will not alter text that has no params, but elements do exist"
  (fmt/format-n "foo" [2 3 4]) => "foo")
 (fact
  "will alter complicated text with params using provided elements"
  (fmt/format-n "list %1 | head" [1]) => "list 1 | head")
 (fact
  "will remove param from text if no supporting elements exist"
  (fmt/format-n "skip %1" []) => "skip "))

(facts
 "about pseudo-format-n"
 (let [args ["foo" "bar" "baz"]]
   (fact
    "swaps out param with all args in list"
    (fmt/pseudo-format-n "all --> %s <-- there" args) =>
    "all --> foo bar baz <-- there")
   (fact
    "swaps out targeted param with defined item in list"
    (fmt/pseudo-format-n "just the second --> %2 <--" args) =>
    "just the second --> bar <--")
   (fact
    "will append to end of str the list of args if no params defined"
    (fmt/pseudo-format-n "append to end -->" args) =>
    "append to end --> foo bar baz"))
 (fact
  "does nothing when no params or args exist"
  (fmt/pseudo-format-n "echo hi | echo bar" []) =>
  "echo hi | echo bar")
 (binding [fmt/*subst-prefix* "\\$"]
   (fact
    "will replace rebound prefix with defined arg in list of args"
    (fmt/pseudo-format-n "foo --> $2 <-- two" [1 2]) =>
    "foo --> 2 <-- two")
   (fact
    "will replace rebound prefix with empty string when defined arg
     in list of args is outside list scope"
    (fmt/pseudo-format-n "is --> $3 <-- empty" [1 2]) =>
    "is -->  <-- empty")
   (fact
    "shouldn't work with old prefix after new one is bound"
    (fmt/pseudo-format-n "--> %s <--" [1 2]) => "--> %s <-- 1 2")
   (fact
    "should replace even if nothing to replace with"
    (fmt/pseudo-format-n "qux --> $s <-- should b empty" []) =>
    "qux -->  <-- should b empty")))

(facts
 "about pseudo-format"
 (fact
  "converts multiple args into string"
  (fmt/pseudo-format "foo" "bar") => "foo bar")
 (fact
  "substitutes middle param when given an arg"
  (fmt/pseudo-format "foo %s baz" "bar") => "foo bar baz")
 (fact
  "substitutes mutliple %s params with given arg"
  (fmt/pseudo-format "foo %s baz %s" "bar") => "foo bar baz bar"))

(facts
 "about limit-and-trim-string-lines"
 (let [s "foo
          bar
          baz
          hi
          ok
          hi"]
   (fact
    "will take defined limit and trim strings (delim :newline) when
     presented with many"
    (fmt/limit-and-trim-string-lines 2 s) => "foo\nbar")
   (fact
    "will 1 string and return it with no delims"
    (fmt/limit-and-trim-string-lines 1 s) => "foo")))

(facts
 "about remove-surrounding-quotes"
 (fact
  "it removes surrounding double quotes"
  (fmt/remove-surrounding-quotes "\"foo bar smack\"") => "foo bar smack")
 (fact
  "it removes surrounding single quotes"
  (fmt/remove-surrounding-quotes "'bbq lolwat'") => "bbq lolwat")
 (fact
  "does not remove inner quotes"
  (fmt/remove-surrounding-quotes "this 'inner' quote") =>
  "this 'inner' quote")
 (fact
  "does not remove mixed quotes"
  (fmt/remove-surrounding-quotes "\"no change'") =>
  "\"no change'"))

(facts
 "about to-coll-if-contains-newlines"
 (fact
  "returns coll if string contains new lines and keeps whitespace"
  (fmt/to-coll-if-contains-newlines "1\n2\n  3") => ["1" "2" "  3"])
 (fact
  "returns arg when no new lines are present"
  (fmt/to-coll-if-contains-newlines "123") => "123"
  (fmt/to-coll-if-contains-newlines 123) => 123))
