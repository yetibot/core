(ns yetibot.core.parser
  (:require [clojure.string :refer [join]]
            [instaparse.core :as insta]
            [taoensso.timbre :refer [info]]
            [yetibot.core.interpreter :refer [handle-expr]]))

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
     <squote> = \"'\"
     literal = quote #'[^\"]+' quote | squote #\"[^']+\" squote
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
        :literal (apply str nodes)
        :sub-expr (let [{:keys [value error] :as evaled}
                        (transformer head)]
                    ;; extract either the error or the value out of the sub-expr
                    (info "sub-expr" head evaled)
                    (or error value))))
    ;; literal
    expr))

(defn parse-and-eval [input]
  (-> input parser transformer))

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
