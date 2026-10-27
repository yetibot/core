# yetibot.core

[![Build Status](https://img.shields.io/travis/com/yetibot/yetibot.core?style=for-the-badge)](https://travis-ci.com/yetibot/yetibot.core)
[![Clojars](https://img.shields.io/clojars/v/yetibot/core?style=for-the-badge)](https://clojars.org/yetibot/core)
[![Codecov](https://img.shields.io/codecov/c/github/yetibot/yetibot.core?style=for-the-badge)](https://codecov.io/gh/yetibot/yetibot.core)
[![Deps](https://img.shields.io/badge/dynamic/json.svg?label=deps&url=https%3A%2F%2Fversions.deps.co%2Fyetibot%2Fyetibot.core%2Fstatus.json&query=%24.stats..[%22out-of-date%22]&suffix=%20out%20of%20date&style=for-the-badge&colorB=lightgrey)](https://versions.deps.co/yetibot/yetibot.core)

Core Yetibot utilities, extracted for shared use among Yetibot and its various
plugins. yetibot.core is not meant to be run standalone, but instead used as a
dependency from another project that provides config and optionally other
Yetibot plugins, private or public.

The majority of Yetibot commands live in the [main Yetibot
repo](https://github.com/yetibot/yetibot).

- [CHANGELOG](doc/CHANGELOG.md)
- [yetibot.com](https://yetibot.com)

## Usage

You can depend on this library to build your own Yetibot plugins.
Building your own commands is dead simple. Here's an example command that
adds two numbers:

```clojure
(ns mycompany.plugins.commands.add
  (:require [yetibot.core.hooks :refer [cmd-hook]]))

(defn add-cmd
  "add <number1> <number2> # Add two numbers"
  [{[_ n1 n2] :match}] (+ (read-string n1) (read-string n2)))

(cmd-hook #"add" ; command prefix
          #"(\d+)\s+(\d+)" add-cmd)
```

See Yetibot's own [commands](https://github.com/devth/yetibot/tree/master/src/yetibot/commands)
for more complex and diverse examples.

## Editing Discord commands

Edit a command message in Discord to rerun it. Yetibot updates its original
text reply in place, including error replies, and removes any extra chunks
left over from a longer previous result. Generated-image attachments are
replaced by a new upload and the old bot message is deleted. Removing the
command from the message removes its tracked replies.

Only actual content edits rerun commands; link-preview updates and duplicate
content events do not. Attachments and author information are retained when
Discord sends a partial update. If an older run finishes after an edit, its
result cannot overwrite the newer reply. Rerunning also repeats the command's
side effects, including paid model/image requests.

Reply tracking covers the 1,000 most recently run commands per Discord
connection and is reset on restart. An edit outside that cache can execute as
a new command, but cannot replace an earlier untracked reply. Replies sent
through the chat adapter are tracked; messages a plugin sends directly through
Discord's API are not.

## Dev postgres

To quickly provision a postgresql database to work against, run:

```bash
docker-compose up -d
```

And use the corresponding connection string in config:

```edn
:yetibot-db-url "postgresql://yetibot:yetibot@postgres:5432/yetibot"
```

## Remote REPL

Yetibot runs an embedded nREPL server on port `65432`. Connect to it via:

```
nrepl://localhost:65432
```

Or replace `localhost` with the remote network address.

## yetibot-dashboard

`yetibot-dashboard` is an NPM module that contains static HTML/JS/CSS for the
Yetibot dashboard. It's served by yetibot.core's Ring server. A public example
can be seen at [public.yetibot.com](https://public.yetibot.com).

To update to the latest version run:

```bash
./bin/yetibot-dashboard
```

This downloads the lateset `yetibot-dashboard` from NPM, extracts it, copies the
build into `resources/public` then creates a git commit with the changes.

## Docs

- [User Guide](https://yetibot.com/user-guide/)
- [Dev Guide](https://yetibot.com/dev-guide/)
- [Ops Guide](https://yetibot.com/ops-guide/)

## Change Log

View the [CHANGELOG](doc/CHANGELOG.md).

## License

Copyright © 2013–2019 Trevor C. Hartman

Distributed under the Eclipse Public License version 1.0.
