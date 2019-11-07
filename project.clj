(defproject yetibot/core "_"
  :description "Core yetibot utilities, extracted for shared use among yetibot
                and its various plugins"
  :url "https://github.com/yetibot/yetibot.core"
  :scm {:name "git" :url "https://github.com/yetibot/yetibot.core"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_username
                                     :password :env/clojars_password
                                     :sign-releases false}]]
  :repl-options {:init-ns yetibot.core.repl
                 :timeout 120000
                 :prompt (fn [ns] (str "\u001B[35m[\u001B[34m" ns
                                       "\u001B[35m] \u001B[37mλ:\u001B[m "))
                 :welcome
                 (do
                   (println)
                   (println
                    (str
                     "\u001B[37m"
                     "  Welcome to the Yetibot dev REPL!"
                     \newline
                     "  Use \u001B[35m(\u001B[34mhelp\u001B[35m) "
                     "\u001B[37mto see available commands."
                     \newline
                     \newline
                     "\u001B[35m    λλλ"
                     "\u001B[m"))
                   (println))}

  :git-version
  {:status-to-version
   (fn [{:keys [timestamp ref-short tag version branch ahead ahead? dirty?] :as git}]
     ;; (assert (not dirty?) "Git workspace is dirty")
     (let [instant (.atZone
                    (if (and timestamp (number? (read-string timestamp)))
                      (java.time.Instant/ofEpochMilli (* 1000 (read-string timestamp)))
                      (java.time.Instant/now))
                    java.time.ZoneOffset/UTC)

           datetime (.format
                     (java.time.format.DateTimeFormatter/ofPattern
                      "yyyyMMdd.HHmmss")
                     instant)]
       (format "%s.%s" datetime ref-short)))}

  ; :aot [yetibot.core.init]
  :resource-paths ["resources"]
  :main yetibot.core.init
  :plugins [[me.arrdem/lein-git-version "2.0.8"]
            [lein-environ "1.1.0"]]
  :profiles {:profiles/dev {}
             :dev [:profiles/dev
                   {:plugins [[lein-midje "3.2.1"]
                              [lein-cloverage "1.1.1"]]
                    :dependencies [[lilactown/punk-adapter-jvm "0.0.10"]
                                   [midje "1.9.6"]
                                   [nubank/matcher-combinators "0.9.0"]]}]
             :test
             {:resource-paths ["test/resources"]
              :env {:yb-adapters-freenode-type "irc"
                    :yb-adapters-freenode-host "irc.freenode.net"
                    :yb-adapters-freenode-port "6667"
                    :yb-adapters-freenode-username "yetibot-test"}}}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.4.500"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.clojure/tools.trace "0.7.10"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [org.clojure/java.classpath "0.3.0"]
                 [org.clojure/core.cache "0.8.2"]
                 [org.clojure/core.memoize "0.8.2"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.3"]
                 [org.clojure/java.jdbc "0.7.10"]
                 [org.postgresql/postgresql "42.2.8"]

                 [stylefruits/gniazdo-jsr356 "1.0.0"]

                 ; DurationFormatUtils for uptime
                 [org.apache.commons/commons-lang3 "3.6"]

                 ; logging
                 [com.taoensso/timbre "4.10.0"]

                 ; parsing
                 [instaparse "1.4.7"]
                 ; parser visualization - disable unless needed
                 ; [rhizome "0.1.9"]
                 ;; natural language parsing
                 [wit/duckling "0.4.24"]

                 ; http
                 [clj-http "3.9.1"]

                 ; github
                 [irresponsible/tentacles "0.6.2"]

                 ; email
                 [com.draines/postal "2.0.2"]
                 [io.forward/clojure-mail "1.0.7" :exclusions [medley]]
                 [medley "1.1.0"]

                 ; chat protocols
                 [irclj "0.5.0-alpha4"]
                 [org.julienxx/clj-slack "0.6.3"]
                 [slack-rtm "0.1.7"]
                 [slack-rtm "0.1.7" :exclusions [[stylefruits/gniazdo]]]

                 ; javascript evaluation
                 [evaljs "0.1.2"]

                 ; ssh
                 [clj-commons/clj-ssh "0.5.15"]

                 ; wordnik dictionary
                 [clj-wordnik "0.1.0-alpha1"]

                 ; json parsing / schema
                 [com.bigml/closchema "0.1.8"]
                 [json-path "1.0.1"]

                 ; utils
                 [funcool/cuerdas "2.1.0"]
                 [clj-stacktrace "0.2.8"]
                 [clj-fuzzy "0.4.1"]
                 [robert/hooke "1.3.0"]
                 [clj-time "0.14.4"] ; includes joda-time
                 [throttler "1.0.0"]
                 [expound "0.7.2"]
                 ; scheduling used for mail. could be replaced by
                 ; hara.io.scheduler
                 [overtone/at-at "1.2.0"]
                 ; scheduling to support `cron` command
                 [zcaudate/hara.io.scheduler "2.8.2"]
                 [inflections "0.13.0"]
                 [environ "1.1.0"]
                 [dec "1.0.1"]
                 ; retry
                 [robert/bruce "0.8.0"]
                 [com.cemerick/url "0.1.1"]
                 ;; [io.aviso/pretty "0.1.34"] ; pretty stacktraces

                 ;monitoring
                 [metrics-clojure "2.10.0"]
                 [metrics-clojure-riemann "2.10.0"]

                 ; web/ring
                 [ring/ring-json "0.4.0"]
                 [ring/ring-core "1.6.3"]
                 [ring-logger-timbre "0.7.6"]
                 [ring.middleware.conditional "0.2.0"]
                 [ring-cors "0.1.12"]
                 [nrepl "0.6.0"]
                 [http-kit "2.3.0"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-session-timeout "0.2.0"]
                 [ring-middleware-format "0.7.4"
                  :exclusions [org.flatland/ordered
                               clj-stacktrace]]
                 ;; use newer flatland with support for java 11
                 [org.flatland/ordered "1.5.7"]

                 ; web
                 [com.walmartlabs/lacinia "0.32.0"] ;; graphql
                 [selmer "1.12.17"]
                 [compojure "1.6.1"]
                 [prone "1.6.1"]
                 [hiccup "1.0.5"]
                 [hickory "0.7.1"] ; parse html into hiccup
                 ]

  :aliases {"test" ["with-profile" "+test" "midje"]}

  ;; release is purely derived from git sha and timestamp 😑,
  ;; so there's no need to commit, bump, or tag anything
  :release-tasks [["vcs" "assert-committed"]
                  ["deploy"]])
