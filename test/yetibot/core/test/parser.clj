(ns yetibot.core.test.parser
  (:require
    [clojure.pprint :refer [pprint]]
    [yetibot.core.parser :refer :all]
    [instaparse.core :as insta]
    [clojure.test :refer :all]))

(deftest single-cmd-test
  (is (= (parser "uptime")
         [:expr [:cmd [:words "uptime"]]]))
  (is (= (parser "echo qux")
         [:expr [:cmd [:words "echo" [:space " "] "qux"]]])
      "Single commands should be parsed"))

(deftest neighboring-sub-exprs
  (is (= (parser "echo $(echo foo)bar")
         [:expr [:cmd [:words "echo" [:space " "] [:sub-expr [:expr [:cmd [:words "echo" [:space " "] "foo"]]]] "bar"]]])))

(deftest sub-expr-evaluation-test
  (let [expr-tree (parser "echo foo `echo foo` bar")]
    (is (= expr-tree
           [:expr [:cmd [:words "echo" [:space " "] "foo" [:space " "]
                         [:sub-expr
                          [:expr [:cmd [:words "echo" [:space " "] "foo"]]]]
                         [:space " "] "bar"]]]
           ))
    (let [{:keys [value]} (transformer expr-tree)]
      (is (= "foo foo bar" value)))))

(deftest piped-cmd-test
  (is
    (= (parser "echo hello | echo bar")
       [:expr [:cmd [:words "echo" [:space " "] "hello"]] [:cmd [:words "echo" [:space " "] "bar"]]])
    "Piped commands should be parsed"))

(deftest sub-expr-test
  (is
    (=
     (parser "echo `catfact` | echo It is known:")
     [:expr [:cmd [:words "echo" [:space " "] [:sub-expr [:expr [:cmd [:words "catfact"]]]]]] [:cmd [:words "echo" [:space " "] "It" [:space " "] "is" [:space " "] "known:"]]])
    "Backtick sub-expressions should be parsed")
  (is
    (=
     (parser "echo $(random)")
     [:expr [:cmd [:words "echo" [:space " "] [:sub-expr [:expr [:cmd [:words "random"]]]]]]])
    "Standard sub-expressions should be parsed"))

(deftest nested-sub-expr-test
  (is
    (=
     (parser "random | buffer | echo `number $(buffer | peek)`")
     [:expr [:cmd [:words "random"]] [:cmd [:words "buffer"]] [:cmd [:words "echo" [:space " "] [:sub-expr [:expr [:cmd [:words "number" [:space " "] [:sub-expr [:expr [:cmd [:words "buffer"]] [:cmd [:words "peek"]]]]]]]]]]])
    "Nested sub-expressions should be parsed")
  (is
    (=
     (parser
       "echo foo
        $(echo bar)")
     [:expr [:cmd [:words "echo" [:space " "] "foo\n" [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:sub-expr [:expr [:cmd [:words "echo" [:space " "] "bar"]]]]]]])
    "Expressions with newlines should preserve the newline")
  (is
    (=
     (parser
       "urban random | buffer | echo `meme wizard: what is $(buffer peek | head)?`
        `meme chemistry: a $(buffer peek | head) is $(buffer peek | head 2 | tail)`")
     [:expr [:cmd [:words "urban" [:space " "] "random"]] [:cmd [:words "buffer"]] [:cmd [:words "echo" [:space " "] [:sub-expr [:expr [:cmd [:words "meme" [:space " "] "wizard:" [:space " "] "what" [:space " "] "is" [:space " "] [:sub-expr [:expr [:cmd [:words "buffer" [:space " "] "peek"]] [:cmd [:words "head"]]]] "?"]]]] "\n" [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:sub-expr [:expr [:cmd [:words "meme" [:space " "] "chemistry:" [:space " "] "a" [:space " "] [:sub-expr [:expr [:cmd [:words "buffer" [:space " "] "peek"]] [:cmd [:words "head"]]]] [:space " "] "is" [:space " "] [:sub-expr [:expr [:cmd [:words "buffer" [:space " "] "peek"]] [:cmd [:words "head" [:space " "] "2"]] [:cmd [:words "tail"]]]]]]]]]]])
    "Complex nested sub-expressions with newlines should be parsed"))

(deftest literal-test
  (is
    (= (parser "alias foo = \"bar\"")
       [:expr [:cmd [:words "alias" [:space " "] "foo" [:space " "] "=" [:space " "] [:literal "\"" "bar" "\""]]]]))
  (is
    (= (parser "meme foo: \"lol")
       [:expr [:cmd [:words "meme" [:space " "] "foo:" [:space " "] "\"" "lol"]]]))
  (is
    (= (parser "foo \"lol | foo\"")
       [:expr [:cmd [:words "foo" [:space " "] [:literal "\"" "lol | foo" "\""]]]]))
  (is
    (= (parser "foo \"lol | foo")
       [:expr [:cmd [:words "foo" [:space " "] "\"" "lol"]] [:cmd [:words "foo"]]])))

(deftest unmatched-parens-test
  (is
    (= (parser " foo)")
       [:expr [:cmd [:words [:space " "] "foo" ")"]]]))
  (is
    (= (parser "foo)")
       [:expr [:cmd [:words "foo" ")"]]]))
  (is
    (= (parser "foo (")
       [:expr [:cmd [:words "foo" [:space " "] "("]]]))
  (is
    (= (parser " foo")
       [:expr [:cmd [:words [:space " "] "foo"]]])))

(deftest special-character-test
  (is (= (parser "foo \"$\"")
         [:expr [:cmd [:words "foo" [:space " "] [:literal "\"" "$" "\""]]]])
      "Parsing a special character works if it's a literal")
  (is (= (parser "foo $")
         [:expr [:cmd [:words "foo" [:space " "] "$"]]])
      "Parsing with a sub-expr special character works as long as it doesn't fit
       the shape of the beginning of a sub-expr, e.g. $(....."))

(deftest regex-pipe-test
  (is (= (parser "grep foo|bar")
         [:expr [:cmd [:words "grep" [:space " "] "foo" "|" "bar"]]])
      "Parser should be able to handle a pipe without whitespace as a literal,
       as in the case of regexes")
  (is (= (parser "grep foo|bar|baz")
         [:expr [:cmd [:words "grep" [:space " "] "foo" "|" "bar" "|" "baz"]]])
      "Parser should be able to parse repeated pipes without surrounding whitespace"))

(deftest significant-whitespace-test
  (is (= (parser "list 1, 2, 3 | join        ")
         [:expr [:cmd [:words "list" [:space " "] "1," [:space " "] "2," [:space " "] "3"]] [:cmd [:words "join" [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "] [:space " "]]]])
      "Parser should preserve whitespace")
  (is (= (parser "list 1, 2, 3 | join  | echo hi")
         [:expr [:cmd [:words "list" [:space " "] "1," [:space " "] "2," [:space " "] "3"]] [:cmd [:words "join" [:space " "]]] [:cmd [:words "echo" [:space " "] "hi"]]])
      "Parser should preserve whitespace"))


;; Failing test for https://github.com/devth/yetibot/issues/423
;; the problem is the parser tries to close the sub-expression too soon, leaving
;; a trailing ) outside of the sub-expr.
#_(deftest preserve-matched-parens-in-subexpr
  (is
    (= (parser "hi $((hi))")
       [:expr [:cmd [:words "hi" [:space " "] [:expr [:cmd [:words "(" "hi" ")"]]]]]]))
  (is (=
       (parser "echo $(clj (+ 1 1))")
       [:expr [:cmd [:words "echo" [:space " "] [:expr [:cmd [:words "clj" [:space " "] "(" "+" [:space " "] "1" [:space " "] "1" ")"]]]]]])))

;; unparsing

(def pipes-sample "foo | bar | echo $(baz))")

(def sub-expr-unparse-sample "foo | echo $(echo `foo`)")

(deftest unparse-test
  (testing "Ability to unparse an expression back into its original string form"
    (is (= pipes-sample (unparse (parser pipes-sample)))))

  (testing "Ability to unparse expression with backtick sub-expressions are
            reconstructed using $() sub-expr syntax i.e. the backticks are not
            preserved."
    (is (= "foo | echo $(echo $(foo))"
           (unparse (parser sub-expr-unparse-sample))))))
