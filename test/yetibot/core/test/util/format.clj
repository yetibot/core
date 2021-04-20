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

(def formatted-list (fmt/format-data-structure nested-list))

(fact
 "the flattened representation should not contain collections"
 (let [[_ flattened] formatted-list]
   flattened =not=> (contains coll?)))

;; TODO port the rest of this to Midje

(facts
 "format-n"
 (fmt/format-n "foo %1" [2]) => "foo 2"
 (fmt/format-n "foo" [2 3 4]) => "foo"
 (fmt/format-n "list %1 | head" [1]) => "list 1 | head")

(facts
 "about pseudo-format-n"
 (let [args ["foo" "bar" "baz"]]
   (fmt/pseudo-format-n
    "all --> %s <-- there" args) => "all --> foo bar baz <-- there"
   (fmt/pseudo-format-n "just the second --> %2 <--" args) =>
   "just the second --> bar <--"
   (fmt/pseudo-format-n "append to end -->" args) =>
   "append to end --> foo bar baz")
 (fmt/pseudo-format-n "echo hi | echo bar" []) =>
 "echo hi | echo bar")

(facts
 "about pseudo-format-n with rebound prefix"
 (binding [fmt/*subst-prefix* "\\$"]
   ;; It should work with a new prefix.
   (fmt/pseudo-format-n "foo --> $2 <-- two" [1 2]) =>
   "foo --> 2 <-- two"
   ;; It shouldn't work with the old prefix after a new one is bound.
   (fmt/pseudo-format-n "--> %s <--" [1 2]) => "--> %s <-- 1 2"))

(facts
 "Basic pseudo-format usage"
 (fmt/pseudo-format "foo" "bar") => "foo bar"
 (fact
  "It substitutes in the middle when it's supposed to")
 (fmt/pseudo-format "foo %s baz" "bar") => "foo bar baz")

(facts
 "replace-even-if-nothing-to-replace-with"
 (binding [fmt/*subst-prefix* "\\$"]
   (fmt/pseudo-format-n "qux --> $s <-- should b empty" []) =>
   "qux -->  <-- should b empty"))

(fact
 "limit-and-trim-string-lines-test"
  (let [s "foo
           bar
           baz
           hi
           ok
           hi"
        expected "foo\nbar"]
    (fmt/limit-and-trim-string-lines 2 s) => expected))

(facts
 "remove-surrounding-quotes"
 (fact
  "it removes surrounding double quotes"
  (fmt/remove-surrounding-quotes "\"foo bar smack\"") => "foo bar smack")
 (fact
  "it removes surrounding single quotes"
  (fmt/remove-surrounding-quotes "'bbq lolwat'") => "bbq lolwat"))

