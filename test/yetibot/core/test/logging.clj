(ns yetibot.core.test.logging
  (:require [yetibot.core.logging :as l]
            [yetibot.core.config :refer [get-config]]
            [midje.sweet :refer [facts fact => provided anything
                                 contains]]))

(facts
 "about log-level"
 (fact
  "it will return a :debug log level when the :value returned from the config
   is 'debug'"
  (l/log-level) => :debug
  (provided (get-config anything anything) => {:value "debug"}))
 
 (fact
  "it will return a :info log level when an :error is present"
  (l/log-level) => :info
  (provided (get-config anything anything) => {:error true})))

(facts
 "about rolling-appender-enabled?"
 (fact
  "it will return true when the value returned from the config is anything but
   'false'"
  (l/rolling-appender-enabled?) => true
  (provided (get-config anything anything) => {:value "doesntmatter"}))
 
 (fact
  "it will return false only when value returned from the config is 'false'"
  (l/rolling-appender-enabled?) => false
  (provided (get-config anything anything) => {:value "false"})))

(facts
 "about start"
 (fact
  "it will default to level :info and rolling-appender enabled when
   no configs are provided"
  (let [config (l/start)]
    config => (contains {:level :info})
    (get-in config [:appenders :rolling-appender]) => (contains
                                                       {:enabled? true}))))

(facts
 "about log-path-config"
 (fact
  "it returns the default log path when custom config is present"
  (l/log-path-config) => l/default-log-path)
 
 (fact
  "it returns a custom log path config'ed for one"
  (l/log-path-config) => :custom-log-path
  (provided (get-config anything anything) => {:value :custom-log-path})))
