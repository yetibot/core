# yetibot.core
[![Build Status](https://travis-ci.org/devth/yetibot.core.svg?branch=master)](https://travis-ci.org/devth/yetibot.core)
[![CrossClj](https://img.shields.io/badge/CrossClj%20Docs-yetibot.core-blue.svg)](https://crossclj.info/doc/yetibot.core/latest/index.html)

Core yetibot utilities, extracted for shared use among yetibot and its various
plugins. yetibot.core is not meant to be run standalone, but instead used as a
dependency from another project that provides config and optionally other
yetibot plugins, private or public.

The main yetibot commands live at:
https://github.com/devth/yetibot

[<img src="http://clojars.org/yetibot.core/latest-version.svg" />](https://clojars.org/yetibot.core)

- [CHANGELOG](doc/CHANGELOG)
- [Docs](doc/)

## Changes in 0.4.0

0.4.0 decomplects mutable and immutable configuration in a
non-backward-compatible way. Please see [CONFIGURATION](doc/CONFIGURATION.md)
docs and port your existing config to the new structure.

## Usage

You can depend on this library to build your own yetibot plugins.
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

See yetibot's own [commands](https://github.com/devth/yetibot/tree/master/src/yetibot/commands)
for more complex and diverse examples.


## Docs

- [Load Order](doc/LOADING.md)
- [Categories](doc/CATEGORIES.md)
- [Command handling pipeline](doc/COMMAND_HANDLING_PIPELINE.md)
- [Yetibot project README](https://github.com/devth/yetibot)

## Change Log

View the [change log](doc/CHANGELOG.md).

## License

Copyright © 2013–2016 Trevor C. Hartman

Distributed under the Eclipse Public License version 1.0.
