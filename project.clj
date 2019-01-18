(defproject yetibot.core "0.4.67-SNAPSHOT"
  :description "Core yetibot utilities, extracted for shared use among yetibot
                and its various plugins"
  :url "https://github.com/yetibot/yetibot.core"
  :scm {:name "git" :url "https://github.com/yetibot/yetibot.core.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["releases" :clojars]]
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
                       "\u001B[m"
                       ))
                   (println))}
  ; :aot [yetibot.core.init]
  :resource-paths ["resources"
                   ;; yetibot-dashboard is an npm dep
                   ;; we serve the static html/css/js out of it to run the SPA
                   ;; dashboard
                   "node_modules/yetibot-dashboard/build"]
  :main yetibot.core.init
  :plugins [[lein-environ "1.1.0"]
            [lein-npm "0.6.2"]]
  :profiles {:profiles/dev {}
             :dev [:profiles/dev
                   {:plugins []}]
             :test
             {:resource-paths ["test/resources"]
              :env {:yb-adapters-freenode-type "irc"
                    :yb-adapters-freenode-host "irc.freenode.net"
                    :yb-adapters-freenode-port "6667"
                    :yb-adapters-freenode-username "yetibot-test"}}}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.3.465"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.cli "0.4.1"]
                 [org.clojure/tools.trace "0.7.10"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [org.clojure/java.classpath "0.3.0"]
                 [org.clojure/core.cache "0.6.5"]
                 [org.clojure/core.memoize "0.5.9"]
                 [org.clojure/core.match "0.3.0-alpha5"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.2"]
                 [org.clojure/java.jdbc "0.7.3"]
                 [org.postgresql/postgresql "42.2.5"]

                 [stylefruits/gniazdo-jsr356 "1.0.0"]

                 ; DurationFormatUtils for uptime
                 [org.apache.commons/commons-lang3 "3.6"]

                 ; logging
                 [com.taoensso/timbre "4.10.0"]

                 ; parsing
                 [instaparse "1.4.7"]
                 ; parser visualization - disable unless needed
                 ; [rhizome "0.1.9"]

                 ; http
                 [clj-http "3.9.1"]

                 ; github
                 [irresponsible/tentacles "0.6.2"]

                 ; email
                 [com.draines/postal "2.0.2"]
                 [io.forward/clojure-mail "1.0.7" :exclusions [medley]]
                 [medley "1.0.0"]

                 ; chat protocols
                 [irclj "0.5.0-alpha4"]
                 [org.julienxx/clj-slack "0.6.2"]
                 [slack-rtm "0.1.7"]

                 ; javascript evaluation
                 [evaljs "0.1.2"]

                 ; ssh
                 [clj-ssh "0.5.14"]

                 ; wordnik dictionary
                 [clj-wordnik "0.1.0-alpha1"]

                 ; json parsing / schema
                 [com.bigml/closchema "0.1.8"]
                 [cheshire "5.8.0"]
                 [prismatic/schema "1.1.6"]
                 [json-path "1.0.1"]

                 ; utils
                 [funcool/cuerdas "2.0.4"]
                 [clj-stacktrace "0.2.8"]
                 [clj-fuzzy "0.4.1"]
                 [robert/hooke "1.3.0"]
                 [clj-time "0.14.4"] ; includes joda-time
                 [rate-gate "1.3.1"]
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

                 ; web/ring
                 [ring/ring-json "0.4.0"]
                 [ring/ring-core "1.6.3"]
                 [ring-logger-timbre "0.7.6"]
                 [ring.middleware.conditional "0.2.0"]
                 [ring-cors "0.1.12"]
                 [nrepl "0.5.3"]

                 ; [ring/ring-jetty-adapter "1.4.0"]
                 [http-kit "2.3.0"]

                 ; [ring-server "0.4.0"]
                 ; [info.sunng/ring-jetty9-adapter "0.8.4"]

                 [ring/ring-defaults "0.3.1"]
                 [ring/ring-session-timeout "0.2.0"]

                 [ring-middleware-format "0.7.2"
                  :exclusions [org.flatland/ordered
                               clj-stacktrace]]
                 ;; use newer flatland with support for java 11
                 [org.flatland/ordered "1.5.7"]

                 ;; [metosin/ring-http-response "0.9.1"]

                 ; web
                 [com.walmartlabs/lacinia "0.31.0-rc-1"] ;; graphql
                 [selmer "1.12.5"]
                 [compojure "1.6.0"]
                 [prone "1.1.4"]
                 [hiccup "1.0.5"]
                 [hickory "0.7.1"] ; parse html into hiccup

                 [slack-rtm "0.1.6" :exclusions [[stylefruits/gniazdo]]]
                 ]

  :release-tasks [["vcs" "assert-committed"]
                  ["deps"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]

  :npm {:dependencies [[yetibot-dashboard "0.7.1"]]})
