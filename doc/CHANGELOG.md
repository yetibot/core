# yetibot.core change log

## 0.4.6

- Add `nil` command (moved from Yetibot)

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

### 0.2.0

- Add support for per-room settings (new data structure in config). Format:

```edn
:irc {:rooms {"#yetibot" {:broadcast? true}
              "#workstuff" {:broadcast? false}
```

The above `:irc` settings would allow yetibot to post Tweets in the #yetibot
channel, but not in the #workstuff channel. Not backwards compatible with old
config
