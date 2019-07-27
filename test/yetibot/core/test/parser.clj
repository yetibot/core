(ns yetibot.core.test.parser
  (:require
    [clojure.pprint :refer [pprint]]
    [midje.sweet :refer [fact facts =>]]
    [yetibot.core.models.karma :refer :all]
    [yetibot.core.parser :refer :all]
    [instaparse.core :as insta]))

(facts "single commands should be parsed"
  (parser "uptime") => [:expr [:cmd [:words "uptime"]]]
  (parser "echo qux") => [:expr [:cmd [:words "echo" [:space " "] "qux"]]])

(fact "Neighboring sub expressions are parsed"
  (parser "echo $(echo foo)bar") =>
  [:expr [:cmd [:words
                "echo" [:space " "]
                [:sub-expr [:expr [:cmd [:words "echo" [:space " "] "foo"]]]]
                "bar"]]])

(fact "Sub exprs should be parsed"
  (parser "echo foo `echo foo` bar") =>
  [:expr [:cmd [:words "echo" [:space " "] "foo" [:space " "]
                [:sub-expr
                 [:expr [:cmd [:words "echo" [:space " "] "foo"]]]]
                [:space " "] "bar"]]])

(fact "Piped commands should be parsed"
  (parser "echo hello | echo bar") =>
  [:expr
   [:cmd [:words "echo" [:space " "] "hello"]]
   [:cmd [:words "echo" [:space " "] "bar"]]])

(fact "Backtick sub-expressions should be parsed"
  (parser "echo `catfact` | echo It is known:") =>
  [:expr
   [:cmd
    [:words "echo" [:space " "] [:sub-expr [:expr [:cmd [:words "catfact"]]]]]]
   [:cmd [:words "echo" [:space " "] "It" [:space " "] "is" [:space " "] "known:"]]])

(fact "Standard sub-expressions should be parsed"
  (parser "echo $(random)") =>
  [:expr [:cmd [:words "echo" [:space " "] [:sub-expr [:expr [:cmd [:words "random"]]]]]]])

(fact "Nested sub-expressions should be parsed"
  (parser "random | buffer | echo `number $(buffer | peek)`") =>
  [:expr
   [:cmd [:words "random"]]
   [:cmd [:words "buffer"]]
   [:cmd
    [:words
     "echo"
     [:space " "]
     [:sub-expr
      [:expr
       [:cmd
        [:words
         "number"
         [:space " "]
         [:sub-expr
          [:expr [:cmd [:words "buffer"]] [:cmd [:words "peek"]]]]]]]]]]])

(fact "Expressions with newlines should preserve the newline"
  (parser "echo foo $(echo bar)") =>
  [:expr
   [:cmd
    [:words
     "echo"
     [:space " "]
     "foo"
     [:space " "]
     [:sub-expr [:expr [:cmd [:words "echo" [:space " "] "bar"]]]]]]])

(fact "Complex nested sub-expressions with newlines should be parsed"
  (parser
    "urban random | buffer | echo `meme wizard: what is $(buffer peek | head)?` `meme chemistry: a $(buffer peek | head) is $(buffer peek | head 2 | tail)`")
  =>
  [:expr
   [:cmd [:words "urban" [:space " "] "random"]]
   [:cmd [:words "buffer"]]
   [:cmd
    [:words
     "echo"
     [:space " "]
     [:sub-expr
      [:expr
       [:cmd
        [:words
         "meme"
         [:space " "]
         "wizard:"
         [:space " "]
         "what"
         [:space " "]
         "is"
         [:space " "]
         [:sub-expr
          [:expr
           [:cmd [:words "buffer" [:space " "] "peek"]]
           [:cmd [:words "head"]]]]
         "?"]]]]
     [:space " "]
     [:sub-expr
      [:expr
       [:cmd
        [:words
         "meme"
         [:space " "]
         "chemistry:"
         [:space " "]
         "a"
         [:space " "]
         [:sub-expr
          [:expr
           [:cmd [:words "buffer" [:space " "] "peek"]]
           [:cmd [:words "head"]]]]
         [:space " "]
         "is"
         [:space " "]
         [:sub-expr
          [:expr
           [:cmd [:words "buffer" [:space " "] "peek"]]
           [:cmd [:words "head" [:space " "] "2"]]
           [:cmd [:words "tail"]]]]]]]]]]])

(facts "literals should be parsed"
  (parser "alias foo = \"bar\"") =>
  [:expr [:cmd [:words "alias" [:space " "] "foo" [:space " "] "=" [:space " "] [:literal "\"" "bar" "\""]]]]

  (parser "meme foo: \"lol") =>
  [:expr [:cmd [:words "meme" [:space " "] "foo:" [:space " "] "\"" "lol"]]]

  (parser "foo \"lol | foo\"") =>
  [:expr [:cmd [:words "foo" [:space " "] [:literal "\"" "lol | foo" "\""]]]]

  (parser "foo \"lol | foo") =>
  [:expr [:cmd [:words "foo" [:space " "] "\"" "lol"]] [:cmd [:words "foo"]]])

(facts "unmatched parens should be ignored by the parser"
  (parser " foo)") =>
  [:expr [:cmd [:words [:space " "] "foo" ")"]]]

  (parser "foo)") =>
  [:expr [:cmd [:words "foo" ")"]]]

  (parser "foo (") =>
  [:expr [:cmd [:words "foo" [:space " "] "("]]]

  (parser " foo") =>
  [:expr [:cmd [:words [:space " "] "foo"]]])

(fact
  "Parsing a special character works if it's a literal"
  (parser "foo \"$\"") =>
  [:expr [:cmd [:words "foo" [:space " "] [:literal "\"" "$" "\""]]]])

(fact
  "Parsing with a sub-expr special character works as long as it doesn't fit
   the shape of the beginning of a sub-expr, e.g. $(....."
  (parser "foo $") =>
  [:expr [:cmd [:words "foo" [:space " "] "$"]]])

(fact
  "Parser should be able to handle a pipe without whitespace as a literal, as in
   the case of regexes"
  (parser "grep foo|bar") =>
  [:expr [:cmd [:words "grep" [:space " "] "foo" "|" "bar"]]])

(fact
  "Parser should be able to parse repeated pipes without surrounding whitespace"
  (parser "grep foo|bar|baz") =>
  [:expr [:cmd [:words "grep" [:space " "] "foo" "|" "bar" "|" "baz"]]])

(fact "Parser should preserve whitespace"
  (parser "list 1, 2, 3 | join        ") =>
  [:expr
   [:cmd [:words "list" [:space " "] "1," [:space " "] "2," [:space " "] "3"]]
   [:cmd [:words "join" [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "]]]])

(fact "Parser should preserve whitespace"
  (parser "list 1, 2, 3 | join  | echo hi") =>
  [:expr [:cmd [:words "list" [:space " "] "1," [:space " "] "2," [:space " "] "3"]] [:cmd [:words "join" [:space " "]]] [:cmd [:words "echo" [:space " "] "hi"]]])

;; Failing test for https://github.com/devth/yetibot/issues/423
;; the problem is the parser tries to close the sub-expression too soon, leaving
;; a trailing ) outside of the sub-expr.
#_(facts "preserve matched parens in subexpr"
    (parser "hi $((hi))") =>
    [:expr [:cmd [:words "hi" [:space " "] [:expr [:cmd [:words "(" "hi" ")"]]]]]]
    (parser "echo $(clj (+ 1 1))") =>
    [:expr [:cmd [:words "echo" [:space " "] [:expr [:cmd [:words "clj" [:space " "] "(" "+" [:space " "] "1" [:space " "] "1" ")"]]]]]])

;; unparsing

(def pipes-sample "foo | bar | echo $(baz))")

(def sub-expr-unparse-sample "foo | echo $(echo `foo`)")

(fact "it can unparse an expression back into its original string form"
  (unparse (parser pipes-sample)) => pipes-sample)

(fact "it can unparse expression with backtick sub-expressions are reconstructed
       using $() sub-expr syntax i.e. the backticks are not preserved."
  (unparse (parser sub-expr-unparse-sample)) =>
  "foo | echo $(echo $(foo))")
