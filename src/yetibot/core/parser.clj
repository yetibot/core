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

(defn- hiccup-transform
  [transform-map parse-tree]
  (info "transform" (pr-str parse-tree))
  (if (and (sequential? parse-tree) (seq parse-tree))
    (do
      #_(when (= :expr (first parse-tree))
          (info "  seq - transform: " (pr-str parse-tree) (transform-map (first parse-tree))))
      (if-let [transform (transform-map (first parse-tree))]
        (merge-meta
          (apply transform (map (partial hiccup-transform transform-map)
                                (next parse-tree)))
          (meta parse-tree))
        (with-meta
          (into [(first parse-tree)]
                (map (partial hiccup-transform transform-map)
                     (next parse-tree)))
          (meta parse-tree))))
    (do
      #_(info "  non sequential pass through" (pr-str parse-tree))
      parse-tree)))

;; current hypothesis:

;; 2 phase parse tree evaluation. sub-expressions are left unexpanded on the
;; first pass so that `pipe-cmd` can lazily expand them. (note: could we put a
;; delay on it or something so that we can keep expansion logic up here?)

(def eval-map
  {:words (fn [& words] (join words))
   :literal str
   :space str
   :parened str
   :cmd identity #_(fn [cmd]
                     (let [result (handle-cmd cmd {})]
                       (info "eval cmd" cmd "result" result)
                       result))
   :sub-expr identity #_(fn [{:keys [value error] :as se}]
                          (info "sub-expr" se)
                          (if (map? se)
                            (or value error)
                            se))
   :expr #'handle-expr
   #_(fn [& cmds]
       (info "expr cmds:" (pr-str cmds))
           ;; (handle-expr cmds)
           ;; pipe the result of each command
       (reduce
        (fn [acc result]
          (if (sequential? result)
                 ;; ignore collection results for now but in reality these
                 ;; would need to be piped in
                 ;; which means we can't do the reducing at this level
                 ;; maybe :expr should be left untransformed and have a separate
                 ;; transformer for the children of an expr? that way this
                 ;; handler can fully realize each command one at a time and
                 ;; pipe a result into the next.
            acc
                 ;; pseudo format would go here
            (str acc " " result)))
        ""
        cmds)

       #_(join " " cmds))
   ;; :expr #'handle-expr
   })

(defn yetibot-transform
  "Transform a hiccup-form Instaparse parse tree by evaluating it"
  [parse-tree]
  ;; (info "yetibot-transform" (pr-str parse-tree))
  (if-let [transform (eval-map (first parse-tree))]
    (let [tag (first parse-tree)
          [head & nodes] (rest parse-tree)
          first-node (yetibot-transform head)]
      ;; special handling for expr piping its commands
      (info "tag" tag)
      (do
        (info "transform" (pr-str parse-tree) transform)
        (info "first-node" first-node)
        (apply transform
               (cons
                first-node
                (map yetibot-transform nodes))))
      #_(condp = tag
          :expr (do
                  (info "expr" (pr-str nodes))
                  (handle-expr nodes))
            ;; else
          ))
    ;; return it as is
    parse-tree))

;; problem: we currently can't support a feature like:
;; !cmd-with-data | echo `render {{x.foo}}` - `render {{x.bar}}`
;; because evaluation of a parse tree would need to evaluate all sub-expressions
;; before finally being able to pipe simple expressions `A | B`.
;;
;; a possible solution:
;;
;; 1. always break :expr trees into separate parse trees to be evaluated
;;    separately
;; 2. the output of the first :expr gets propagated as input to the next :expr -
;;
;; note that we need to propagate all :result/* fields:
;; - :result/value
;; - :result/data
;; - :result/error

;; (doall (map yetibot-transform (next parse-tree)))

(def transformer yetibot-transform)
  ;; (partial
  ;;   transformer
  ;;   eval-map))


(defn parse-and-eval [input]
  (-> input parser yetibot-transform))

(comment

  (parse-and-eval
   "category | echo `render {{async}}`")

  (parse-and-eval
   "echo num one `echo one.a` | echo num two `echo two.a`")

  (parse-and-eval
    "echo num one `echo one.a` | echo num two `echo two.a`")

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
    "range 2 | data show | echo "

    )

  (parse-and-eval
    "echo foo | echo bar"

    )
  )

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
