(defproject yetibot.core "0.4.30-SNAPSHOT"
  :description "Core yetibot utilities, extracted for shared use among yetibot
                and its various plugins"
  :url "https://github.com/yetibot/yetibot.core"
  :scm {:name "git" :url "https://github.com/yetibot/yetibot.core.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["releases" :clojars]]
  :repl-options {:init-ns yetibot.core.repl
                 :timeout 120000
                 :welcome (println "Welcome to the Yetibot development repl!")}
  ; :aot [yetibot.core.init]
  :main yetibot.core.init
  :plugins [[lein-environ "1.0.3"]]
  :profiles {:profiles/dev {}
             :dev [:profiles/dev
                   {:plugins [[venantius/ultra "0.5.1"]]}]
             :test
             {:resource-paths ["test/resources"]
              :env {:yb-adapters-freenode-type "irc"
                    :yb-adapters-freenode-host "irc.freenode.net"
                    :yb-adapters-freenode-port "6667"
                    :yb-adapters-freenode-username "yetibot-test"}}}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.3.465"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.nrepl "0.2.13"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/tools.trace "0.7.9"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [org.clojure/java.classpath "0.2.3"]
                 [org.clojure/core.cache "0.6.5"]
                 [org.clojure/core.memoize "0.5.9"]
                 [org.clojure/core.match "0.3.0-alpha5"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.2"]
                 [org.clojure/java.jdbc "0.7.3"]
                 [org.postgresql/postgresql "42.1.4"]

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
                 [clj-http "3.7.0"]

                 ; github
                 [tentacles "0.5.1" :exclusions [[org.clojure/clojure]]]

                 ; email
                 [com.draines/postal "2.0.2"]
                 [io.forward/clojure-mail "1.0.7" :exclusions [medley]]
                 [medley "1.0.0"]

                 ; chat protocols
                 [irclj "0.5.0-alpha4"]
                 [org.julienxx/clj-slack "0.5.5"]
                 [slack-rtm "0.1.6"]

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
                 [clj-time "0.14.0"] ; includes joda-time
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
                 [io.aviso/pretty "0.1.34"] ; pretty stacktraces

                 ; web/ring
                 [ring/ring-json "0.4.0"]
                 [ring/ring-core "1.6.2"]
                 [ring-logger-timbre "0.7.5"]

                 ; [ring/ring-jetty-adapter "1.4.0"]
                 [http-kit "2.2.0"]

                 ; [ring-server "0.4.0"]
                 ; [info.sunng/ring-jetty9-adapter "0.8.4"]

                 [ring/ring-defaults "0.3.1"]
                 [ring/ring-session-timeout "0.2.0"]

                 [metosin/ring-middleware-format "0.6.0" :exclusions [clj-stacktrace]]
                 [metosin/ring-http-response "0.9.0"]

                 ; web
                 [com.walmartlabs/lacinia "0.23.0"] ;; graphql
                 [selmer "1.11.0"]
                 [compojure "1.6.0"]
                 [prone "1.1.4"]
                 [hiccup "1.0.5"]
                 ; [markdown-clj "0.9.66"]

                 [slack-rtm "0.1.6" :exclusions [[stylefruits/gniazdo]]]
                 ])
