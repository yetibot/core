(defproject yetibot.core "0.3.18-SNAPSHOT"
  :description "Core yetibot utilities, extracted for shared use among yetibot
                and its various plugins"
  :url "https://github.com/devth/yetibot.core"
  :scm {:name "git" :url "https://github.com/devth/yetibot.core.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["releases" :clojars]]
  :repl-options {:init-ns yetibot.core.repl
                 :timeout 120000
                 :welcome (println "Welcome to the Yetibot development repl!")}
  ; :aot [yetibot.core.init]
  :main yetibot.core.init
  :plugins [[lein-environ "1.0.3"]]
  :profiles {:test {:resource-paths ["test/resources"]}}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.385"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojure/tools.trace "0.7.9"]
                 [org.clojure/java.classpath "0.2.3"]
                 [org.clojure/core.cache "0.6.5"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [org.clojure/core.memoize "0.5.9"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.2"]
                 [org.clojure/java.jdbc "0.6.1"]

                 ; DurationFormatUtils for uptime
                 [org.apache.commons/commons-lang3 "3.4"]

                 ; logging
                 [com.taoensso/timbre "4.7.3"]

                 ; parsing
                 [instaparse "1.4.2"]
                 ; parser visualization - disable unless needed
                 ; [rhizome "0.1.9"]

                 ; http
                 [clj-http "3.1.0"]

                 ; github
                 [tentacles "0.5.1" :exclusions [[org.clojure/clojure]]]

                 ; email
                 [com.draines/postal "2.0.1"]
                 [clojure-mail "0.1.6"]

                 ; chat protocols
                 [irclj "0.5.0-alpha4"]
                 [org.julienxx/clj-slack "0.5.2.1"]
                 [devth/slack-rtm "0.1.0"]

                 ; database
                 [com.datomic/datomic-free "0.9.5302" :exclusions [joda-time]]
                 [datomico "0.2.0"]

                 ; javascript evaluation
                 [evaljs "0.1.2"]

                 ; ssh
                 [clj-ssh "0.5.14"]

                 ; dynamic dependency reloading / adding
                 [com.cemerick/pomegranate "0.3.1"]

                 ; wordnik dictionary
                 [clj-wordnik "0.1.0-alpha1"]

                 ; json parsing / schema
                 [com.bigml/closchema "0.1.8"]
                 [cheshire "5.6.3"]
                 [prismatic/schema "1.1.3"]

                 ; utils
                 [clj-stacktrace "0.2.8"]
                 [clj-fuzzy "0.2.1"]
                 [robert/hooke "1.3.0"]
                 [clj-time "0.12.0"] ; includes joda-time
                 [rate-gate "1.3.1"]
                 [overtone/at-at "1.2.0"]
                 [inflections "0.12.2"]
                 [environ "1.0.3"]
                 [dec "1.0.1"]
                 ; retry
                 [robert/bruce "0.8.0"]
                 [com.cemerick/url "0.1.1"]

                 ; web/ring
                 [ring/ring-json "0.4.0"]
                 [ring/ring-core "1.5.0"]
                 [ring-logger-timbre "0.7.5"]

                 ; [ring/ring-jetty-adapter "1.4.0"]
                 [http-kit "2.2.0"]

                 ; [ring-server "0.4.0"]
                 ; [info.sunng/ring-jetty9-adapter "0.8.4"]


                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-session-timeout "0.1.0"]

                 [metosin/ring-middleware-format "0.6.0"]
                 [metosin/ring-http-response "0.8.0"]

                 ; web
                 [selmer "1.0.7"]
                 [compojure "1.5.1"]
                 [prone "1.1.1"]
                 [hiccup "1.0.5"]
                 ; [markdown-clj "0.9.66"]

                 ])
