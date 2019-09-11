(ns yetibot.core.parser
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.interpreter :refer [pipe-cmds handle-cmd handle-expr]]
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
   ;; i think this needs to happen lazily by the expr handler
   :cmd (fn [cmd]
          (info "eval cmd" cmd)
          cmd
          #_(let [result (handle-cmd cmd {})]
              (info "eval cmd" cmd "result" result)
              result))
   :sub-expr identity #_(fn [{:keys [value error] :as se}]
                          (info "sub-expr" se)
                          (if (map? se)
                            (or value error)
                            se))
   :expr ;; #'handle-expr
   (fn [& cmds]
     (info "expr cmds:" (pr-str cmds))
       ;; (handle-expr cmds)
       ;; pipe the result of each command

     cmds
     #_(reduce
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

     #_(join " " cmds))})


;; (defn handle-expr [expr])

(defn eval-yb-expression [expr]
  (info "eval" expr)
  (if (vector? expr)
    (let [[tag & nodes] expr
          head (first nodes)]
      ;; (info "tag" tag)
      ;; (info "head" head)
      ;; (info "nodes" nodes)
      ;; (cons head (map eval-yb-expression nodes))

      (condp = tag
        :cmd (apply str (map eval-yb-expression nodes))

        ;; this is where the magic happens ✨
        ;; eval cmds in order of left to right (lazily)
        ;; piping the result of one to the next

        :expr (handle-expr #'eval-yb-expression nodes)

        ;; :expr (let [head-cmd-result (eval-yb-expression head)]
        ;;         ;; (info "fully evaluate" head)
        ;;         ;; (info "ready to run" (pr-str head-cmd-result))
        ;;         ;; (info "then pipe the result to" (second nodes))
        ;;         ;; this is where we handle piping
        ;;         (reduce
        ;;          (fn [acc [cmd-ast & next-cmds]]
        ;;            (let [{previous-value :value
        ;;                   previous-data-collection :data-collection
        ;;                   previous-data :data} acc
        ;;                  cmd (eval-yb-expression cmd-ast)]
        ;;              (info "eval" cmd-ast)
        ;;              (info "eval'd" cmd)
        ;;              (info "next is" next-cmds)
        ;;              cmd))
        ;;          {:settings {} ;; TODO
        ;;           :skip-next-n (atom 0)
        ;;           :value ""
        ;;           :data nil}
        ;;          (partition-all (count nodes) 1 nodes)))

        :words (join (map eval-yb-expression nodes))
        :space (apply str nodes)
        :parened (apply str nodes)
        :sub-expr (let [{:keys [value error] :as evaled} (eval-yb-expression head)]
                    ;; extract either the error or the value out of the sub-expr
                    (info "sub-expr" head evaled)
                    (or error value))))
    ;; literal
    expr))

;; (eval-yb-expression
;;  [:cmd [:words "category" [:space " "] "names"]])


(comment
  (require 'yetibot.core.hooks)
  (eval-yb-expression
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
      parser eval-yb-expression)


  (-> "category names | data show"
      parser eval-yb-expression)


  (-> "echo there | echo `echo hi`"
      parser
      eval-yb-expression)

  [:expr
   [:cmd [:words "echo" [:space " "] "there"]]
   [:cmd
    [:words
     "echo"
     [:space " "]
     [:sub-expr [:expr [:cmd [:words "echo" [:space " "] "hi"]]]]]]])



(defn yetibot-transform
  "Transform a hiccup-form Instaparse parse tree by evaluating it"
  [parse-tree]
  (println)
  (println " ")
  (println " ")
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
                (map yetibot-transform nodes)))))
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
