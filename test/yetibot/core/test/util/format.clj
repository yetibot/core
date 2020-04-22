(ns yetibot.core.test.util.format
  (:require
   [midje.sweet :refer [=> =not=> contains fact facts]]
   [yetibot.core.util.format :refer :all]
   [clojure.test :refer :all]))

(def nested-list
  [["meme generator"
    "http://assets.diylol.com/hfs/de6/c7c/061/resized/mi-bok-meme-generator-i-look-both-ways-before-crossing-the-street-at-the-same-time-0dd824.jpg"]
   ["meme maker"
    "http://cdn9.staztic.com/app/a/128/128867/meme-maker-27-0-s-307x512.jpg"]
   ["meme creator"
    "http://img-ipad.lisisoft.com/img/2/9/2974-1-meme-creator-pro-caption-memes.jpg"]])

(def formatted-list (format-data-structure nested-list))

(fact
 "the flattened representation should not contain collections"
 (let [[formatted flattened] formatted-list]
   flattened =not=> (contains coll?)))

;; TODO port the rest of this to Midje

(facts
 "format-n"
 (format-n "foo %1" [2]) => "foo 2"
 (format-n "foo" [2 3 4]) => "foo"
 (format-n "list %1 | head" [1]) => "list 1 | head")

(facts
 pseudo-format-n-test
 (let [args ["foo" "bar" "baz"]]
   (pseudo-format-n
    "all --> %s <-- there" args) => "all --> foo bar baz <-- there"
   (pseudo-format-n "just the second --> %2 <--" args) =>
   "just the second --> bar <--"
   (pseudo-format-n "append to end -->" args) =>
   "append to end --> foo bar baz")
 (pseudo-format-n "echo hi | echo bar" []) =>
 "echo hi | echo bar")

(facts
  pseudo-format-n-with-rebound-prefix
  (binding [*subst-prefix* "\\$"]
    ;; It should work with a new prefix.
    (pseudo-format-n "foo --> $2 <-- two" [1 2]) =>
    "foo --> 2 <-- two"
    ;; It shouldn't work with the old prefix after a new one is bound.
    (pseudo-format-n "--> %s <--" [1 2])
    "--> %s <-- 1 2"))

(facts
 "Basic pseudo-format usage"
 (pseudo-format "foo" "bar") => "foo bar"
 (fact
  "It substitutes in the middle when it's supposed to")
 (pseudo-format "foo %s baz" "bar") => "foo bar baz")

(facts
 "replace-even-if-nothing-to-replace-with"
 (binding [*subst-prefix* "\\$"]
   (pseudo-format-n "qux --> $s <-- should b empty" []) =>
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
    (limit-and-trim-string-lines 2 s) => expected))

(facts
 "remove-surrounding-quotes"
 (fact
  "it removes surrounding double quotes"
  (remove-surrounding-quotes "\"foo bar smack\"") => "foo bar smack")
 (fact
  "it removes surrounding single quotes"
  (remove-surrounding-quotes "'bbq lolwat'") => "bbq lolwat"))

