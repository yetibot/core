;; Sample profiles.clj for Yetibot configuration.
;; equivalent to config.sample.edn
;;
;; It defines a dev profile, but you may want to share much of the configuration
;; between dev and prod, using Composite Profiles, optionally overriding
;; specific differences between dev and prod:
;; https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md#composite-profiles
;;
;; Config is loaded using `environ:` https://github.com/weavejester/environ
;; And exploded into nested maps using `dec`: https://github.com/devth/dec

{:dev
 {:env
  {:yetibot-log-level "debug"

   ;; By default Yetibot uses the ! prefix to match commands.
   ;; You can use this configuration to customize the prefix used by Yetibot
   :yetibot-command-prefix ","
   ;; Whether or not embedded commands should be globally available (enabled by
   ;; default)
   :yetibot-command-embedded-enabled "false"
   ;; Whether to enable having a fallback command. Default is true.
   :yetibot-command-fallback-enabled "true"

   ;; Yetibot needs a Postgres instance to run against.
   :yetibot-db-url "postgresql://localhost:5432/yetibot"
   :yetibot-db-table-prefix "yetibot_"

   ;; ADAPTERS

   ;; Yetibot can listen on multiple instances of each adapters type. Current
   ;; adapter types are Slack and IRC.
   ;;
   ;; Each config map must have:
   ;; - a unique key (i.e. uuid)"
   ;; - a :type key with value "slack" or "irc"
   ;;
   ;; Example configuring 3 adapters: 2 Slacks and 1 IRC:
   :yetibot-adapters-myteam-type "slack"
   :yetibot-adapters-myteam-token "xoxb-111111111111111111111111111111111111"

   :yetibot-adapters-k8s-type "slack"
   :yetibot-adapters-k8s-token "xoxb-k8s-slack-9999999999999999"

   :yetibot-adapters-freenode-type "irc"
   :yetibot-adapters-freenode-host "chat.freenode.net"
   :yetibot-adapters-freenode-port "7070"
   :yetibot-adapters-freenode-ssl "true"
   :yetibot-adapters-freenode-username "yetibot"

   ;; Listens on port 3000 but this may be different for you if you (e.g. if you
   ;; use a load balancer or map ports in Docker).
   :yetibot-url "http://localhost:3000"

   ;;
   ;; WORK
   ;;

   :yetibot-github-token ""
   :yetibot-github-org-0 ""
   :yetibot-github-org-1 ""
   ;; :endpoint is optional: only specify if using GitHub Enterprise.
   :yetibot-github-endpoint ""

   ;; `jira`
   :yetibot-jira-domain ""
   :yetibot-jira-user ""
   :yetibot-jira-password ""
   :yetibot-jira-projects-0-key "FOO"
   :yetibot-jira-projects-0-default-version-id "42"
   :yetibot-jira-default-issue-type-id "3"
   :yetibot-jira-subtask-issue-type-id "27"
   :yetibot-jira-default-project-key "Optional"

   ;; s3
   :yetibot-s3-access-key ""
   :yetibot-s3-secret-key ""

   ;; send and receive emails with `mail`
   :yetibot-mail-host ""
   :yetibot-mail-user ""
   :yetibot-mail-pass ""
   :yetibot-mail-from ""
   :yetibot-mail-bcc ""

   ;;
   ;; FUN
   ;;

   ;;  `giphy`
   :yetibot-giphy-key ""

   ;; the default command to fall back to if no other commands match
   :yetibot-default-command "giphy"

   ;; `meme`
   :yetibot-imgflip-username ""
   :yetibot-imgflip-password ""

   ;;
   ;; INFOs
   ;;

   ;; `google`
   :yetibot-google-api-key ""
   :yetibot-google-custom-search-engine-id ""
   :yetibot-google-options-safe "high"

   ;; `ebay`
   :yetibot-ebay-appid ""

   ;; `twitter`: stream tweets from followers and followed topics directly into
   ;; chat, and post tweets
   :yetibot-twitter-consumer-key ""
   :yetibot-twitter-consumer-secret ""
   :yetibot-twitter-token ""
   :yetibot-twitter-secret ""
   ;; ISO 639-1 code: http://en.wikipedia.org/wiki/List-of-ISO-639-1-codes
   :yetibot-twitter-search-lang "en"

   ;; `jen` - Jenkins
   ;; Jenkins instances config are mutable, and are therefore not defined in
   ;; this config. Instead, add them at runtime. See `!help jen` for more info.

   ;; How long to cache Jenkins jobs from each instance before refreshing
   :yetibot-jenkins-cache-ttl "3600000"
   ;; Default job across all instances, used by `!jen build`
   :yetibot-jenkins-default-job ""
   :yetibot-jenkins-instances-0-name "yetibot"
   :yetibot-jenkins-instances-0-uri "http://yetibot/"
   :yetibot-jenkins-instances-0-default-job "default-job-name"
   ;; If your Jenkins doesn't require auth, set user and api-key to some
   ;; non-blank value in order to pass the configuration check.
   :yetibot-jenkins-instances-0-user "jenkins-user"
   :yetibot-jenkins-instances-0-apikey "abc"
   ;; additional instances can be configured by bumping the index
   :yetibot-jenkins-instances-1-name "yetibot.core"
   :yetibot-jenkins-instances-1-uri "http://yetibot.core/"

   ;; Admin section controls which users have admin privileges and which
   ;; commands are locked down to admin use only.
   ;;
   ;; Set of Strings: Slack IDs or IRC users (which have ~ prefixes) of users
   ;; who can use the yetibot `eval` command.
   :yetibot-admin-users-0 "U123123"
   :yetibot-admin-users-1 "~awesomeperson"
   ;; The set of commands to restrict to admins only (note `eval` is *always*
   ;; admin only regardless of config):
   :yetibot-admin-commands-0 "observer"
   :yetibot-admin-commands-1 "obs"

   ;; Configure GitHub if you have your own fork of the yetibot repo. This will
   ;; allow opening feature requests on your fork.
   :yetibot-features-github-token ""
   :yetibot-features-github-user ""

   ;; SSH servers are specified in groups so that multiple servers which share
   ;; usernames and keys don't need to each specify duplicate config. Fill in
   ;; your own key-names below instead of `:server-a-host`. This is the short
   ;; name that the ssh command will refer to, e.g.: `ssh server-a-host ls -al`.
   :yetibot-ssh-groups-0-key "path-to-key"
   :yetibot-ssh-groups-0-user ""
   :yetibot-ssh-groups-0-servers-0-name ""
   :yetibot-ssh-groups-0-servers-0-host ""
   :yetibot-ssh-groups-0-servers-1-name ""
   :yetibot-ssh-groups-0-servers-1-host ""

   ;; `weather`
   :yetibot-weather-wunderground-key ""
   :yetibot-weather-wunderground-default-zip ""

   ;; `wolfram`
   :yetibot-wolfram-appid ""

   ;; `wordnik` dictionary
   :yetibot-wordnik-key ""

   ;; nrepl configuration
   :yetibot-nrepl-port ""

   ;; `karma`
   :yetibot-karma-emoji-positive ":taco:"
   :yetibot-karma-emoji-negative ":poop:"
   }}}
