(ns yetibot.core.parser
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.interpreter :refer [handle-cmd handle-expr]]
    [clojure.string :refer [join]]
    [instaparse.transform :refer [merge-meta]]
    [instaparse.core :as insta]))

(def parser
  "Major components of the parser:
   expr     - the top-level expression made up of cmds and sub-exprs. When multiple
              cmds are present, it implies they should be successively piped.
   cmd      - a single command consisting of words.
   sub-expr - a backticked or $(..)-style sub-expression to be evaluated inline.
   parened  - a grouping of words wrapped in parenthesis, explicitly tokenized to
              allow parenthesis in cmds and disambiguate between sub-expression
              syntax.
   literal  - a grouping of any characters but quote surrounded by quotes.
              Allows the use of pipes and any other special characters to be
              treated as literal rather than being parsed."
  (insta/parser
    "expr = cmd (<space> <pipe> <space> cmd)*
     cmd = words
     sub-expr = <backtick> expr <backtick> | nestable-sub-expr
     <nestable-sub-expr> = <dollar> <lparen> expr <rparen>
     <non-nestable-sub-expr> = dollar !lparen
     words = space* word (space* word)* space*
     <word> = sub-expr | word-chars | lparen | rparen | quote | literal | regex-pipe | non-nestable-sub-expr
     <word-chars> = #'[^ `$()|\"]+'
     parened = lparen words rparen
     <regex-pipe> = word-chars pipe word-chars (pipe word-chars)*
     <quote> = '\"'
     literal = quote #'[^\"]+' quote
     space = ' '
     <pipe> = #'[|]'
     <dollar> = '$'
     <lparen> = '('
     <rparen> = ')'
     <backtick> = <'`'>"))

(defn transformer
  "This is the AST interpreter. It's an alternative to the transformer that
   comes with Instaparse insta/transform which simply evaluates the tree depth
   first."
  [expr]
  (if (vector? expr)
    (let [[tag & nodes] expr
          head (first nodes)]
      ;; handle the AST nodes adcording to the type of tag
      (condp = tag
        :cmd (apply str (map transformer nodes))
        ;; this is where the magic happens ✨
        ;; eval cmds in order of left to right (lazily)
        ;; piping the result of one to the next
        :expr (handle-expr #'transformer nodes)
        :words (join (map transformer nodes))
        :space (apply str nodes)
        :parened (apply str nodes)
        :sub-expr (let [{:keys [value error] :as evaled}
                        (transformer head)]
                    ;; extract either the error or the value out of the sub-expr
                    (info "sub-expr" head evaled)
                    (or error value))))
    ;; literal
    expr))

(comment
  (require 'yetibot.core.hooks)
  (transformer
   [:expr
    [:cmd [:words "category" [:space " "] "names"]]
    [:cmd
     [:words
      "echo"
      [:space " "]
      "async:"
      [:sub-expr
       [:expr [:cmd [:words "render" [:space " "] "{{async}}"]]]]]]
    [:cmd [:words "echo" [:space " "] "lol"]]])

  (-> "category names | echo async: `render {{async}}` ci: `render {{ci}}`"
      parser transformer)


  (parser
  "category names | echo async: `render {{async}}` ci: `render {{ci}}`"
  )

  (-> "category names | data show"
      parser transformer)


  (-> "echo there | echo `echo hi`"
      parser
      transformer)

  [:expr
   [:cmd [:words "echo" [:space " "] "there"]]
   [:cmd
    [:words
     "echo"
     [:space " "]
     [:sub-expr [:expr [:cmd [:words "echo" [:space " "] "hi"]]]]]]])


(defn parse-and-eval [input]
  (-> input parser transformer))

(comment

  (parse-and-eval "category names")

  ;; this is an example of what we want to achieve:
  ;; propagating the data from `category names`
  (parse-and-eval
   "category names | echo `render {{async}}`")

  (parse-and-eval "category names | echo hi ")

  (parse-and-eval
   "echo num one `echo one.a` | echo num two `echo two.a`")

  (parse-and-eval
    "echo num one `echo one.a` | echo num two `echo two.a`")

  (tap> (parser
    "category names | echo `render {{async}}`"))

  (parser
   "category names | echo `render {{async}}` | echo lol")
  ;; =>
  [:expr
   [:cmd [:words "category" [:space " "] "names"]]
   [:cmd
    [:words
     "echo"
     [:space " "]
     [:sub-expr
      [:expr [:cmd [:words "render" [:space " "] "{{async}}"]]]]]]]


[:expr
   [:cmd [:words "category" [:space " "] "names"]]
   [:cmd
    [:words
     "echo"
     [:space " "]
     [:sub-expr
      [:expr [:cmd [:words "render" [:space " "] "{{async}}"]]]]]]]

  (yetibot-transform
    [:expr
     [:cmd
      [:words
       "echo num one "
       [:sub-expr [:expr [:cmd [:words "echo one.a"]]]]]]
     [:cmd
      [:words
       "echo num two"
       [:space " "]
       [:sub-expr [:expr [:cmd [:words "echo two.a"]]]]]]]
    )

  [:expr
   [:cmd
    [:words
     "echo"
     [:space " "]
     "num"
     [:space " "]
     "one"
     [:space " "]
     [:sub-expr [:expr [:cmd [:words "echo" [:space " "] "one.a"]]]]]]
   [:cmd
    [:words
     "echo"
     [:space " "]
     "num"
     [:space " "]
     "two"
     [:space " "]
     [:sub-expr [:expr [:cmd [:words "echo" [:space " "] "two.a"]]]]]]]

  (parse-and-eval
    "echo num one | echo num three `echo num two`")

  (clojure.pprint/pprint (parser
     "echo num one | echo num three `echo num two`"))

  [:expr
   [:cmd [:words "echo" [:space " "] "num" [:space " "] "one"]]
   [:cmd
    [:words
     "echo"
     [:space " "]
     "num"
     [:space " "]
     "three"
     [:space " "]
     [:sub-expr
      [:expr
       [:cmd [:words "echo" [:space " "] "num" [:space " "] "two"]]]]]]]

  (parse-and-eval
    "range 2 | data show | echo ")

  (parse-and-eval
    "echo foo | echo bar"))

(defn reconstruct-pipe [cmds] (join " | " cmds))

(def unparse-transformer
  "Takes a parse tree and turns it back into a string"
  (partial
    insta/transform
    {:words str
     :literal str
     :space str
     :parened str
     :cmd identity
     ;; avoid wrapping the top level :expr in a subexpression when unparsing
     :sub-expr str
     :expr (fn [& cmds] (str "$(" (reconstruct-pipe cmds)  ")"))
     }))

(defn unparse [parse-tree]
  (let [cmds (rest parse-tree) ]
    (reconstruct-pipe (unparse-transformer cmds))))
