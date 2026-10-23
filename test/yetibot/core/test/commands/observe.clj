(ns yetibot.core.test.commands.observe
  (:require [midje.sweet :refer [=> facts fact contains]]
            [clojure.string :as string]
            [yetibot.core.commands.observe :as obs]
            [yetibot.core.db.observe :as model]
            [yetibot.core.interpreter :as interpreter]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.command :as command]))

(facts
 "about commands.observe"
 (fact
  "returns observed options that have no errors, contains the specified arg,
   and recognizes the -u option with provided value"
  (let [{:keys [arguments errors options]} (obs/parse-observe-opts
                                            "-u lol x")]
    errors => empty?
    arguments => (contains "x")
    (:user-pattern options) => "lol"))
 (fact
  "it will return an error when providing invalid options, but still will parse
   whatever valid options it can find"
  (let [{:keys [arguments errors options]} (obs/parse-observe-opts
                                            "-u lol -ewat x")]
    errors => coll?
    (first errors) => (contains "Failed to validate")
    arguments => (contains "x")
    (:user-pattern options) => "lol"))
 (fact "formatted observers start with a copyable command, then metadata"
  (with-redefs [command/config-prefix "!"]
    (obs/format-observer {:pattern "speech balloon" :cmd "echo {{body}}"
                          :event-type "react" :id 7 :user-id "alice"
                          :user-pattern "^bob$" :channel-pattern "^general$"})
    => (str "!obs -e react -u ^bob$ -c ^general$ speech balloon"
            " = \"echo {{body}}\"\n[id 7] [created by alice]")))
 (fact "blank patterns are omitted, not replaced by a noncopyable label"
  (with-redefs [command/config-prefix "?"]
    (obs/format-observer {:pattern nil :cmd "echo {{username}} joined"
                          :event-type "enter"})
    => "?obs -e enter = \"echo {{username}} joined\"")))

(facts "copying a listed command recreates its options and delayed body"
  (doseq [event-type ["message" "react" "enter" "leave" "sound" "kick"]
          cmd ["echo {{body}}" "echo {{body}} | upper"
               "echo \"quoted | text\" | upper"
               "echo don't | upper" "echo \"say 'hi'\" | upper"
               "echo $(echo later) | upper" "echo \\d+ | upper"]]
    (let [observer {:event-type event-type :pattern "hello|world"
                    :user-pattern "^alice$" :channel-pattern "^general$"
                    :cmd cmd :id 42 :user-id "old-owner"}
          created (atom [])
          line (first (string/split-lines (obs/format-observer observer)))]
      (with-redefs [model/create #(swap! created conj %)
                    channel/settings-for-chat-source (constantly {})]
        (binding [interpreter/*chat-source* {:room "general"}
                  interpreter/*current-user* {:username "new-owner"}
                  interpreter/*yetibot-user* {}]
          (when-let [input (second (command/extract-command line))]
            (parser/parse-and-eval input))))
      (fact (count @created) => 1)
      (fact (select-keys (first @created)
                         [:event-type :pattern :user-pattern
                          :channel-pattern :cmd])
            => (select-keys observer [:event-type :pattern :user-pattern
                                      :channel-pattern :cmd])))))

(fact "help examples create the advertised observer kinds and delayed pipeline"
  (let [examples (map second
                      (re-seq #"(?m)^\s+(!obs .+ = .+)$"
                              (:doc (meta #'obs/observe-cmd))))
        created (atom [])]
    (with-redefs [model/create #(swap! created conj %)
                  channel/settings-for-chat-source (constantly {})]
      (binding [interpreter/*chat-source* {:room "general"}
                interpreter/*current-user* {:username "alice"}
                interpreter/*yetibot-user* {}]
        (doseq [line examples]
          (parser/parse-and-eval (second (command/extract-command line))))))
    (count @created) => 7
    (mapv :event-type @created)
    => ["message" "react" "react" "enter" "leave" "message" "message"]
    (mapv :pattern (take 5 @created))
    => ["hello" "💬" "speech balloon" nil nil]
    (select-keys (nth @created 5) [:user-pattern :channel-pattern])
    => {:user-pattern "^alice$" :channel-pattern "^general$"}
    (:cmd (last @created)) => "echo {{body}} | upper"))

(fact "unquotable stored commands do not break listings or acknowledgments"
  (let [observer {:id 8 :event-type "message" :pattern "hello"
                  :cmd "echo 'a | b' \"c | d\""}]
    (obs/format-observer observer) => (contains "[not copyable]")
    (obs/wire-observer observer) => (contains "Created observer")))

(fact "list output preserves observer data and creation acknowledgments"
  (let [observer {:id 7 :event-type "message" :pattern "hello"
                  :cmd "echo hi" :user-id "alice"}]
    (with-redefs [model/find-all (constantly [observer])]
      (first (:result/value (obs/list-observers)))
      => (contains "obs -e message hello = \"echo hi\"")
      (:result/data (obs/list-observers)) => [observer]
      (obs/wire-observer observer) => (contains "Created observer"))))
