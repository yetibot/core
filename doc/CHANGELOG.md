# yetibot.core change log

## 0.5.7 - 3/3/2019

- Move `karma` from yetibot to yetibot.core -
  [#68](https://github.com/yetibot/yetibot.core/pull/68) by
  [@jcorrado](https://github.com/jcorrado)

## 0.5.6 - 3/3/2019

- Fixup history commands being formatted incorrectly

## 0.5.5 - 3/1/2019

- Detect URLs that have &t=.jpg as images too

## 0.5.4 - 3/1/2019

- Output images as Slack Blocks

## 0.5.3 - 3/1/2019

- Fix bug in `emoji` where a space on the end would cause an NPE and add better
  error reporting if the user doesn't provide a valid emoji
- Fix broken `repeat` and add test coverage

## 0.5.2 - 2/27/2019

- Fix bug that could cause `users` to blow up

## 0.5.1 - 2/27/2019

- Botched release

## 0.5.0 - 2/24/2019

- Mutable config is fully deprecated and removed! See [the blog
  post](https://yetibot.com/blog/2019-02-20-moving-mutable-config-to-the-database)
  for details on reasoning and how to migrate old configuration if necessary.
  [#61](https://github.com/yetibot/yetibot.core/pull/61)
- Remove `room` method from `Adapter`

## 0.4.67 - 2/18/2018

- Ensure `head` and `tail` always return a string for individual values.
  Related to [#829](https://github.com/yetibot/yetibot/issues/829).
- Upgrade to `yetibot-dashboard 0.7.2`

## 0.4.66 - 01/18/2018

- Fix setting Yetibot graphql endpoint in dashboard before the JS that uses it
  loads

## 0.4.65 - 01/17/2018

- Fixup `yetibot-dashboard` to serve new assets by parsing the `index.html`
  output by `yetibot-dashboard`'s `create-react-app` scripts.
- Upgrade to yetibot-dashboard 0.7.1

## 0.4.64 - 01/13/2018

- Improve Slack connection logic: when ping timeout is surpassed the Slack
  adapter will now report `false` for `connected?`
- Upgrade to yetibot-dashboard 0.7.0
- Fix `!!`

## 0.4.63 - 01/04/2018

- Upgrade to Clojure 1.10 -
  [#59](https://github.com/yetibot/yetibot.core/pull/59)
- Rename room command to channel -
  [#58](https://github.com/yetibot/yetibot.core/pull/58) by
  [@kaffein](https://github.com/kaffein)

## 0.4.62 - 12/12/18

- Fix bug in `render` when collections were passed across a pipe, e.g.:

  ```
  list 1 2 3 | render foo
  ```

## 0.4.61 - 12/12/18

- Make nrepl port configurable - [#57](https://github.com/yetibot/yetibot.core/pull/57)
  by [@kaffein](https://github.com/kaffein)
- Add `render` command for templating data across pipes

## 0.4.60 - 12/8/2018

- Replace `"_"` with `" "` on reactions from react observers, e.g.
  `thinking_face` becomes `thinking face`
- Re-hookup the `flatten` command (it was removed at some point)

## 0.4.59 - 12/6/2018

- Remove duplicates in `category list <category>` command
- Add `:collection` metadata to all the collection commands so they properly
  show up in the `!category list collection` command

## 0.4.58 - 12/4/2018

- Fixup bug in xargs where raw `:result/data` / `:result/value` data structures
  were returned instead of being extracted
- Post react observer reactions on the thread of the message reacted to

## 0.4.57 - 12/3/2018

- React event support for observers!
  [#756](https://github.com/yetibot/yetibot/issues/756)

  This enables fun Slack-specific behavior where a reaction can trigger a
  Yetibot command.

  For example, to generate a rage meme whenever someone reacts with 😡 on a
  message:

  ```
  !obs -e react rage = meme rage: {{body}}
  ```

  See the docs on `!help observe` for more info!

## 0.4.56 - 11/30/2018

- Ignore `on-message-changed` events in Slack if user is Yetibot to avoid double
  recording history. When Slack unfurls things it fires a message changed event.

## 0.4.55 - 11/30/2018

- Fix `!that` command

## 0.4.54 - 11/16/2018

- Fix evaluation of subexpressions -
  [#55](https://github.com/yetibot/yetibot.core/pull/55)

## 0.4.53 - 11/4/2018

- Support `HAVING` clause in `y.c.db.util/query` -
  [#54](https://github.com/yetibot/yetibot.core/pull/54) by
  [@jcorrado](https://github.com/jcorrado)

## 0.4.52 - 11/4/2018

- Upgrade to `org.clojure/java.classpath "0.3.0"`
- Throw an error if db namespaces to load are empty

## 0.4.51 - 11/4/2018

- Add new `record-and-run-raw` function to handle recording history and
  evaluating expressions via the main pipeline (via `handle-raw`) and also for
  alias, observers, and cron. This fixes a major bug in aliases that was
  introduced in 0.4.47.

## 0.4.50 - 11/3/2018

- Remove fenced code block formatting on multiline Slack messages since it
  breaks Emoji and other Slack formatting niceties.

## 0.4.49 - 11/2/2018

- Add GROUP BY to db/util.
  [#50](https://github.com/yetibot/yetibot.core/pull/50) by
  [@jcorrado](https://github.com/jcorrado)

## 0.4.48 - 10/29/2018

- Fix buggy empty collection checking in xargs
- Allow suppression meta to work on collections of suppressed items

## 0.4.47 - 10/29/2018

- Allow xargs to intelligently use `pmap` or `map` depending on whether the
  command it's executing has `:async` in its `:yb/cat` set. [#49](https://github.com/yetibot/yetibot.core/pull/49)
  by [@jcorrado](https://github.com/jcorrado)
- Encode explicit errors - [#48](https://github.com/yetibot/yetibot.core/pull/48)
- Prevent embedded commands from producing errors -
  [#767](https://github.com/yetibot/yetibot/issues/767)

## 0.4.46 - 10/23/2018

- Add `raw all` command to show all command args
- Render multi line messages in Slack as code

## 0.4.45 - 10/20/2018

- Add `command-execution-info` function to help testing command parsing and
  regex matching - [#43](https://github.com/yetibot/yetibot.core/pull/43)
- Fix `users` in Slack - [#555](https://github.com/yetibot/yetibot/issues/555)

## 0.4.44 - 10/16/2018

- Use ping/pong events in Slack to monitor the connection
- Expose connection latency metrics on GraphQL `:adapters` resolver
- Upgrade to `yetibot-dashboard 0.6.6`

## 0.4.43 - 10/15/2018

- Improve logging in interpreter

## 0.4.42 - 10/13/2018

- GraphQL updates
  - Added `user` resolver and added `user` field on `history` type
  - Added `channels` resolver to list all channels for all adapters
  - Added history_item` resolver to get a single history item by ID
- Upgrade Slack libraries [#42](https://github.com/yetibot/yetibot.core/pull/42)
  by [@cvic](https://github.com/cvic)
- Switch to `irresponsible/tentacles` [#41](https://github.com/yetibot/yetibot.core/pull/41)
  by [@cvic](https://github.com/cvic)
- Enable remote nREPL on port `65432` [#44](https://github.com/yetibot/yetibot.core/pull/44)
- Upgrade to `yetibot-dashboard 0.6.5`

## 0.4.41 - 9/28/2018

- Upgrade to `yetibot-dashboard 0.6.0`
- Add support for `yetibot_only` in GraphQL History resolver
- Fix airity bug when trying to reconnect to Slack
- Use `to_tsquery` instead of `to_tsquery` in history resolver search to support
  queries with spaces

## 0.4.40 - 9/24/2018

- Add `/healthz` endpoint [#691](https://github.com/yetibot/yetibot/issues/691)

## 0.4.39 - 6/29/2018

- Upgrade to `yetibot-dashboard 0.5.4`
- Add cache-busting query param with the hash of vendor.js and main.js
- Support new `search_query` param on history resolver
- Add support for `commands_only` in history resolver

## 0.4.38 - 6/24/2018

- Upgrade to yetibot-dashboard 0.5.0

## 0.4.37 - 6/24/2018

- Add new `is_private` column to history table and consider a message in Slack
  adapter private if it's either a direct message or from within a group.
- Elide `is_private` entities from history in GraphQL API

## 0.4.36 - 6/23/2018

- Add ability to obtain GraphQL endpoint from env

## 0.4.35 - 6/23/2018

- Massively increase GraphQL coverage to support yetibot-dashboard
- Consume npm dep `yetibot-dashbaord` and use its JavaScript output to render
  the dashboard and sub-routes

## 0.4.34 - 5/29/2018

- Prevent Yetibot from triggering itself -
  [#40](https://github.com/yetibot/yetibot.core/pull/40)

## 0.4.33 - 5/9/2018

- Fix `config-prefix` requires now that it moved to `yetibot.core.util.command`
- Add `yetibot.core.test.loader` to load all observers and commands at test time
  to find bad requires earlier on

## 0.4.32 - 5/9/2018

- Add `yetibot.core.parser/unparse` to take an expression tree and unparse it
  back to the original string that produced it when parsed.

- Record Yetibot's output in the history table for all adapters (Slack, IRC).
  History is now recorded directly in the `yetibot.core/handler/handle-raw`
  function instead of the old `history-observer`, which was removed. This is
  because `handle-raw` has all the context to create history for both users and
  Yetibot, including the Yetibot user and a new computed `correlation-id`,
  computed as:

  ```clojure
       (let [timestamp (System/currentTimeMillis)
             correlation-id (str timestamp "-"
                                 (hash [chat-source user event-type body]))]
         ;; ...
         )
  ```

  The `correlation-id` is stored in both the user's command (request) history
  entry and Yetibot's evaluation (response) history entry so the two can be
  easily correlated.

  This supports [History of Yetibot output for a given command
  #728](https://github.com/yetibot/yetibot/issues/728).

- Added new `command` text column to the history table to be used to record the
  request command that correlates with Yetibot's response. As such, this column
  is only set on `:is-yetibot true` columns

## 0.4.31 - 4/26/2018

- Fix `bot_mesage` -> `bot_message` typo that was preventing bot messages from
  being observed in Slack

## 0.4.30 - 4/26/2018

- Record all Yetibot history in the database
- Add subcommands on history to show all Yetibot history or all non-Yetibot
  history
- Support listening to Slack attachment style messages
- Attempt to idempotently add columns to db tables on startup if the table
  already exists. This allows adding columns to tables over time (but never
  changing or deleting columns).

## 0.4.29 - 3/28/2018

- Switch eval type on GraphQL endpoint to `(list String)` to support expressions
  that return lists

## 0.4.28 - 3/28/2018

- Add initial GraphQL with `eval` query support. Example:

  ```
  {eval(expr: "echo foo | echo bar")}
  ```

- Enable `Access-Control-Allow-Origin *` header so we can hit GraphQL endpoint
  from the docs to support interactive docs

## 0.4.27

- Accurately record history using adapter UUID instead of type

## 0.4.26

- Add `cron` command! -
  [yetibot/yetibot#150](https://github.com/yetibot/yetibot/issues/150)

## 0.4.25

- Reply to threads in Slack

## 0.4.24

- `users` now lists all users in a room, regardless of whether they are active
  or not. This is due to Slack's [Changes to
  presence](https://api.slack.com/changelog/2018-01-presence-present-and-future#happens)
  that makes it much harder to listen for `presence_change` events.

## 0.4.23

- Change `YETIBOT_CONFIG_DISABLED` to `YETIBOT_ENV_CONFIG_DISABLED` and log when
  it's disabled

## 0.4.22

- Add support to ignore all env-based config if `YETIBOT_CONFIG_DISABLED` is set
  to a value

## 0.4.21

- Listen to `bot_message` event subtypes in Slack

## 0.4.20

- Upgrade deps to fix warnings after Clojure 1.9 upgrade
- Fix eval - [#645](https://github.com/yetibot/yetibot.core/pull/32) by
  [@cvic](https://github.com/cvic)

## 0.4.19

- Upgrade to Clojure 1.9

## 0.4.18

- Allow env-based config to override individual values of edn-based config -
  [#690](https://github.com/yetibot/yetibot/issues/690)
- Run custom observer handlers in a separate thread and never spew an exception
  - [#448](https://github.com/yetibot/yetibot/issues/448)
- Allow `nil` to suppress output -
  [#618](https://github.com/yetibot/yetibot/issues/618)
- Detect nil user ID when a user tries to create an alias -
  [#667](https://github.com/yetibot/yetibot/issues/667)

## 0.4.17

- Migrate from Datomic to Postgres
- Move `!` and `that` into yetibot.core

## 0.4.16

This release focuses on observer power ups 💪

- Room specific observers -
  [#29](https://github.com/yetibot/yetibot.core/pull/29)
- Enable enter and leave observers
- Enable template access to channel name and username in observers
  [#694](https://github.com/yetibot/yetibot/issues/694)

Examples:

1. Welcome users to the `#general` channel when they join:

   ```
   !obs -eenter -cgeneral = echo welcome to #general, {{username}}!
   ```

1. Send a user a private message when they join the `#dev` channel:

   ```
   !obs -eenter -cdev = "echo hi {{username}} welcome to #dev! | replyto {{username}}"
   ```

It also works for `leave` events:

```
!obs -eleave -cdev = "echo hi {{username}} welcome to #dev! | replyto {{username}}"
```

## 0.4.15

- Prevent IPV6 issues with nrepl start-server

## 0.4.14

- Use default port of 3003
- Fix encoding of @here and @channel in Slack

## 0.4.13

- Fix reading string port from the environment as an integer

## 0.4.12

- Run the web server on port specified by environment variable `PORT` defaulting
  to 3000 if not specified

## 0.4.11

- Support passing extra data across pipes -
  [#28](https://github.com/yetibot/yetibot.core/pull/28)
- Fix errors on Slack startup -
  [#680](https://github.com/yetibot/yetibot/issues/680)

## 0.4.10

- Upgrade all the outdated deps
- Add support for a configurable default command and remove broken Bing and
  Google Image Search commands

## 0.4.9

- Bind *adapter* inside each IRC event handler. This was preventing the IRC
  adapter from functioning.
- Allow custom prefix. ([#23](https://github.com/devth/yetibot.core/pull/23),
  [@dawsonfi](https://github.com/dawsonfi))

## 0.4.8

- Add Yetibot image to about command.
  ([#22](https://github.com/devth/yetibot.core/pull/22),
  [jkieberk](https://github.com/jkieberk)

## 0.4.7

- Fix error when attempting to create mutable config on startup but the `config`
  dir doesn't exist by using `make-parents` to ensure the dir exists.

## 0.4.6

- Add `nil` command (moved from Yetibot)
- Add `react` command for Slack reactions

## 0.4.5

- Allow creating observers for specific user patterns -
  [#605](https://github.com/devth/yetibot/issues/605)

## 0.4.4

- Update images to new Yetibot design

## 0.4.3

- Decode slack url encodings when there are multiple urls in a message -
  [#603](https://github.com/devth/yetibot/issues/603)

## 0.4.2

- Improve Slack url unencoding and add tests. Before it only properly unencoded
  urls like `<Google|http://google.com>`. Now it also unencodes
  `<https://www.google.com/>`.

## 0.4.1

- Fix fn airity bug in IRC where multi-line messages would error

## 0.4.0

- Refactor configuration:
  It was getting quite unweidly and inconsistent with somewhat arbitrary
  hierarchies.
  - Separate out the mutable parts (e.g. IRC room config) from actual immutable
    configuration.
  - Use [Environ](https://github.com/weavejester/environ) for immutable config
    and [dec](https://github.com/devth/dec) to explode flat config into nested
    maps
  - Use `yetibot.core.config-mutable` for mutable parts
  - Use [schema](https://github.com/plumatic/schema) to validate the expected
    shape of config when obtaining via `get-config`
- Moved the ssh command out of yetibot and into yetibot.core
- Upgraded to Clojure 1.8.0
- Upgraded many dependencies

## 0.3.17

- Listen to Slack edit events and re-execute command
  [devth/yetibot.core#483](https://github.com/devth/yetibot/issues/483) by
  @LeonmanRolls

## 0.3.15

- add logging to web requests

## 0.3.14

- add tentacles dependency

## 0.3.13

- fix global anchor styles
- configure CodeClimate
- upgrade minor versions of some of the outdated deps to latest:
  - [cheshire "5.5.0"]
  - [environ "1.0.2"]
  - [http-kit "2.1.19"]
  - [org.clojure/core.cache "0.6.4"]
  - [org.clojure/core.memoize "0.5.8"]
  - [org.clojure/java.classpath "0.2.3"]
  - [org.clojure/tools.namespace "0.2.11"]
  - [org.clojure/tools.nrepl "0.2.12"]
  - [org.clojure/tools.trace "0.7.9"]

## 0.3.12

- add `url` command to post Yetibot's configured web address
- load routes from plugins for matching namespaces
- simplify web view and style
- ensure discovered db schemas are unique
- reduce Slack logging

## 0.3.11

- fix botched 0.3.10 release due to compiler error

## 0.3.10

- support serving web routes in plugins. Namespaces should match:

  ```
  #"^.*plugins\.routes.*"
  ```

  And each namespace must have a symbol `routes` that contains the routes.

## 0.3.9

- reduce logging
- upgrade data.xml to 0.0.8
- error and exit if config is missing; [#532](https://github.com/devth/yetibot/issues/532)

## 0.3.8

- stop loading config on namespace load; load it explicitly
- fix chat-sources in Slack to use channel names instead of IDs

## 0.3.7

- fix airity bug in update-settings that prevented being able to set a room
  setting

## 0.3.6

- fix datomic warnings
- upgrade Clojure from 1.6 1.7
- upgrade Instaparse from 1.2.2 to 1.4.1

## 0.3.5

- further improve disabled category messaging

## 0.3.4

- improve disabled category messaging
- remove `fun` from default room settings since it's now controlled by
  `category`

## 0.3.3

- Support categories for commands and enabling/disabling them for specific
  rooms. All categories are enabled by default. Categories docs are at
  [CATEGORIES](doc/CATEGORIES.md). See command usage with `help category`.

## 0.3.2

- Re-release due to Clojars downtime

## 0.3.1

- Fix unwords when a non-collection is passed to it
- Pass room settings as `:settings` key to all command handlers
- Fix bug where yetibot was observing itself, causing potential infinite loops

## 0.3.0

- Deprecated Campfire adapter but left code in place in case anyone wants to
  port it to the new protocol-based adapter system. If you use Campfire and want
  it to be supported but don't want to PR let me know and I'll re-add it. It was
  removed because I wasn't sure anyone uses Campfire.

- Huge refactoring of adapters. Each adapter is now an instance of an Adapter
  protocol, and there can be any number of the various types of adapters
  (currently Slack & IRC).

  Rooms are now configured independent of adapter configuration. Configuration
  is non-backward compatible. See
  [config-sample.edn](https://github.com/devth/yetibot/blob/master/config/config-sample.edn) for details.

- Refactored room settings. Arbitrary config can now be set on a room, e.g.

  ```
  !room set jira-project YETI
  ```

  Defaults for known settings are:

  | Name            | Default |
  | --------------- | ------- |
  | broadcast       | false   |
  | jira-project    | ""      |
  | jenkins-default | ""      |

  But you can also set any key/val you like.

  To view settings use `!room settings`.

  Full `room` docs:

  ```
  room settings <key> # show the value for a single setting
  room join <room> # join <room>
  room leave <room> # leave <room>
  room list # list rooms that yetibot is in
  room settings # show all chat settings forall rooms
  room set <key> <value> # configure a setting for the current room
  ```

- Disalbed AOT

## 0.2.0

- Add support for per-room settings (new data structure in config). Format:

```edn
:irc {:rooms {"#yetibot" {:broadcast? true}
              "#workstuff" {:broadcast? false}
```

The above `:irc` settings would allow yetibot to post Tweets in the #yetibot
channel, but not in the #workstuff channel. Not backwards compatible with old
config
