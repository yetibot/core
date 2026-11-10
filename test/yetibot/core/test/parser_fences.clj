(ns yetibot.core.test.parser-fences
  (:require [midje.sweet :refer [fact =>]]
            [yetibot.core.interpreter :as interpreter]
            [yetibot.core.parser :as parser]
            [yetibot.core.unparser :as unparser]))

(fact "complete Markdown fences are literal, including pipes and executable-looking text"
  (doseq [marker ["```" "~~~~"]]
    (let [source (str marker "mermaid\ngraph TD\n A[\"x | y $(cron delete) `command`\"]\n" marker)
          input (str "kroki render mermaid " source)
          calls (atom 0)]
      (unparser/unparse (parser/parser input)) => input
      (with-redefs [interpreter/handle-expr
                    (fn [evaluate cmds]
                      (swap! calls inc)
                      {:value (mapv evaluate cmds)})]
        (:value (parser/parse-and-eval input)) => [input]
        @calls => 1))))

(fact "a fence-like prefix within the source does not terminate the literal block"
  (let [input "echo ```txt\nfirst\n```not-closing\n$(cron delete)\n```"
        calls (atom 0)]
    (unparser/unparse (parser/parser input)) => input
    (with-redefs [interpreter/handle-expr
                  (fn [evaluate cmds]
                    (swap! calls inc)
                    {:value (mapv evaluate cmds)})]
      (:value (parser/parse-and-eval input)) => [input]
      @calls => 1)))

(fact "a pipeline after a fenced source remains an ordinary outer pipeline"
  (let [input "echo ```txt\na | b\n``` | echo done"
        tree (parser/parser input)]
    (count (rest tree)) => 2
    (unparser/unparse tree) => input))
