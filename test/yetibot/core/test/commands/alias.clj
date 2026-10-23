(ns yetibot.core.test.commands.alias
  (:require [midje.sweet :refer [facts fact => every-checker against-background
                                 provided contains truthy falsey]]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.db.alias :as model]
            [yetibot.core.hooks :as hooks :refer [cmd-unhook]]
            [yetibot.core.commands.help :as command-help]
            [yetibot.core.handler :as handler]
            [yetibot.core.util.command :as command]
            [yetibot.core.models.help :as help]
            [midje.util :refer [testable-privates]]))

(facts
 "about add-alias"
 (let [cmd-name "hello"
       alias-info {:cmd-name cmd-name
                   :cmd "echo hello"
                   :user-id 123}
       alias-id {:id 123}]
   (fact
    "will update an alias, with its ID, when the existing alias cmd name
     already exists"
    (alias/add-alias alias-info) => alias-info
    (provided (#'alias/existing-alias cmd-name) => alias-id
              (model/update-where alias-id alias-info) => nil))
   (fact
    "will create a new alias when the cmd name doesn't already exist"
    (alias/add-alias alias-info) => alias-info
    (provided (#'alias/existing-alias cmd-name) => nil
              (model/create alias-info) => nil))))

(facts
 "about list-aliases"
 (let [aliases [{:cmd-name "hello" :cmd "echo hello"}]]
   (fact
    "when aliases exist, returns result data and values, no errors"
    (:result/data (alias/list-aliases :ignored)) => aliases
    (provided (model/find-all) => aliases))
   (fact
    "when no aliases exist, returns result error with string"
    (:result/error (alias/list-aliases :ignored)) => string?
    (provided (model/find-all) => []))))

(facts
 "about remove-alias"
 (let [cmd "hello"
       id 123
       alias {:match [:random cmd]}]
   (fact
    "when an alias matches, returns result error with string"
    (:result/data (alias/remove-alias alias))
    => (contains {:id id :cmd cmd})
    (provided (#'alias/existing-alias cmd) => {:id 123}
              (model/delete id) => :diddelete
              (cmd-unhook cmd) => :didunhook))
   (fact
    "when no aliases matches, returns result error with string"
    (:result/error (alias/remove-alias alias))
    => (every-checker string?
                      (contains cmd))
    (provided (#'alias/existing-alias cmd) => nil))))

(facts "loading saved aliases registers every row without per-alias queries"
  ;; Finish the namespace's boot task before isolating the global registries.
  (try (deref alias/loader) (catch Exception _ nil))
  (let [rows [{:cmd-name "sayhi" :cmd "echo hi $1" :user-id "alice"}
              {:cmd-name "mp" :cmd "meme preview" :user-id "bob"}]
        queries (atom 0)
        runs (atom [])]
    (with-redefs [hooks/hooks (atom {})
                  hooks/re-prefix->topic (atom {})
                  help/docs (atom {"my" #{"my <key> # unrelated help"}})
                  help/alias-docs (atom {})
                  model/find-all (constantly rows)
                  model/query (fn [_]
                                (swap! queries inc)
                                (throw (IllegalArgumentException.
                                         "extra connection failed")))
                  handler/record-and-run-raw
                  (fn [& args]
                    (swap! runs conj args)
                    [{:result "alias result"}])]
      (fact "the bulk read is enough to restore both aliases"
        (alias/load-aliases) => nil)
      (fact "registration never opens a second database connection"
        @queries => 0)
      (doseq [{:keys [cmd-name cmd]} rows]
        (fact "saved aliases have executable hooks and exact help"
          (first (hooks/find-sub-cmds cmd-name)) => (str "^" cmd-name "$")
          (command-help/help-for-topic {:args cmd-name})
          => [(str cmd-name " # alias for " cmd)]))
      (let [f (second (hooks/match-sub-cmds
                        "bob" (last (hooks/find-sub-cmds "sayhi"))))
            user {:username "alice"}
            bot {:username "yetibot"}]
        (fact "restored aliases preserve argument substitution and execution"
          (when f (f {:args "bob" :user user :yetibot-user bot}))
          => "alias result"
          @runs => [[(str command/config-prefix "echo hi bob") user bot
                     {:record-yetibot-response? false}]])))))

(facts "creating and replacing aliases retain their acknowledgments"
  (doseq [[old expected] [[nil "mp alias created"]
                         [{:cmd "echo old"}
                          "Replaced existing alias mp. Was `echo old`"]]]
    (let [stored (atom [])]
      (with-redefs [hooks/hooks (atom {})
                    hooks/re-prefix->topic (atom {})
                    help/docs (atom {})
                    help/alias-docs (atom {})
                    model/find-all (constantly [])
                    model/query (constantly (if old [old] []))
                    alias/add-alias (fn [row] (swap! stored conj row))]
        (fact "creation and replacement keep their existing response text"
          (alias/create-alias {:match [nil "mp" "meme preview"]
                               :user {:username "alice"}}) => expected
          @stored => [{:cmd-name "mp" :cmd "meme preview"
                       :user-id "alice"}]
          (command-help/help-for-topic {:args "mp"})
          => ["mp # alias for meme preview"])))))

(testable-privates yetibot.core.commands.alias cleaned-cmd-name)
(facts
 "about cleaned-cmd-name"
 (fact
  "returns 'cleaned' 1st element of command string args"
  (cleaned-cmd-name "  hello world  ") => "hello"))

(testable-privates yetibot.core.commands.alias built-in?)
(facts
 "about built-in? (all builtin commands w/ docs - all alias commands)"
 (against-background (help/get-docs) => {"echo" {}}
                     (model/find-all) => [{:cmd-name "alias1"}])
 (fact
  "returns the name of the command if considered a built-in"
  (built-in? "echo") => truthy)
 (fact
  "returns nil if NOT considered a built-in"
  (built-in? "alias1") => falsey))
