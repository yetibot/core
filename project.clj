(defproject yetibot.core "0.3.15"
  :description "Core yetibot utilities, extracted for shared use among yetibot
                and its various plugins"
  :url "https://github.com/devth/yetibot.core"
  :scm {:name "git" :url "https://github.com/devth/yetibot.core.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :lein-release {:deploy-via :clojars}
  :deploy-repositories [["releases" :clojars]]
  :repl-options {:init-ns yetibot.core.repl
                 :timeout 120000
                 :welcome (println "Welcome to the yetibot development repl!")}
  ; :aot [yetibot.core.init]
  :main yetibot.core.init
  :profiles {:test {:resource-paths ["test/resources"]}}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.338.0-5c5012-alpha"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojure/tools.trace "0.7.9"]
                 [org.clojure/java.classpath "0.2.3"]
                 [org.clojure/core.cache "0.6.4"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [org.clojure/core.memoize "0.5.8"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/java.jdbc "0.2.3"]

                 ; DurationFormatUtils for uptime
                 [org.apache.commons/commons-lang3 "3.1"]

                 ; logging
                 [devth/timbre "3.3.1"]

                 ; parsing
                 [instaparse "1.4.1"]
                 ; parser visualization - disable unless needed
                 ; [rhizome "0.1.9"]

                 ; http
                 [clj-http "0.7.7"]
                 [http.async.client "0.5.2" :exclusions [[org.slf4j/slf4j-api]
                                                         [io.netty/netty]]]

                 ; github
                 [tentacles "0.5.1" :exclusions [[org.clojure/clojure]]]

                 ; email
                 [com.draines/postal "1.9.0"]
                 [clojure-mail "0.1.4"]

                 ; chat protocols
                 [clj-campfire "2.2.0"]
                 [irclj "0.5.0-alpha4"]
                 [org.julienxx/clj-slack "0.5.2.1"]
                 [devth/slack-rtm "0.1.0"]

                 ; database
                 [com.datomic/datomic-free "0.9.5302" :exclusions [joda-time]]
                 [datomico "0.2.0"]

                 ; javascript evaluation
                 [evaljs "0.1.2"]

                 ; ssh
                 [clj-ssh "0.4.0"]

                 ; dynamic dependency reloading / adding
                 [com.cemerick/pomegranate "0.3.0"]

                 ; wordnik dictionary
                 [clj-wordnik "0.1.0-alpha1"]

                 ; json parsing / schema
                 [com.bigml/closchema "0.1.8"]
                 [cheshire "5.5.0"]

                 ; utils
                 [clj-stacktrace "0.2.8"]
                 [clj-fuzzy "0.2.1"]
                 [robert/hooke "1.3.0"]
                 [clj-time "0.11.0"] ; includes joda-time
                 [rate-gate "1.3.1"]
                 [overtone/at-at "1.0.0"]
                 [inflections "0.7.3"]
                 [environ "1.0.2"]
                 ; retry
                 [robert/bruce "0.8.0"]
                 [com.cemerick/url "0.1.1"]

                 ; web/ring
                 [ring/ring-json "0.3.1"]
                 [ring/ring-core "1.4.0"]
                 ;; [ring-logger "0.7.5"]
                 [ring-logger-timbre "0.7.5"]

                 ; [ring/ring-jetty-adapter "1.4.0"]
                 [http-kit "2.1.19"]

                 ; [ring-server "0.4.0"]
                 ; [info.sunng/ring-jetty9-adapter "0.8.4"]


                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-session-timeout "0.1.0"]

                 [metosin/ring-middleware-format "0.6.0"]
                 [metosin/ring-http-response "0.6.5"]

                 ; web
                 [selmer "0.8.2"]
                 [compojure "1.4.0"]
                 [prone "0.8.2"]
                 [hiccup "1.0.5"]
                 ; [markdown-clj "0.9.66"]

                 ])
