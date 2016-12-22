# Configuration

See [profiles.sample.clj](../config/profiles.sample.clj) or
[config.sample.edn](../config/config.sample.edn) for examples of configuring Yetibot;
these are equivalent and both immutable.

## Rationale

It's useful to change the configuration of a system at runtime in certain
situtations. It would be burdensome to have to login to a system where Yetibot
runs, change config and restart Yetibot.

On the other hand, the benefits of immutability are well-known. Explicitly
separating out the small amount of mutable config from the majority of immutable
config lets us maximize immutability benefits and minimize negative affects of
mutability in our system.

In the future we may move all mutable config to the database. The only reason
not to is because when using the default in-memory database all customizations
would be lost upon restart.

## Modes

Yetibot supports both immutable and mutable configuration.

- **Immutable config sources** include both `profiles.clj` and environmental
  variables via `environ` or loading EDN from a file by specifying a
  `CONFIG_PATH` env var. If `CONFIG_PATH` is not specified Yetibot will attempt
  to load all config using `environ`. Providing config via multiple methods
  makes it compatible with 12-factor configuration and simple usage in container
  environments while still retaining the ease of of use of the edn file option.

  The majority of configurable sub-systems use immutable config as they do not
  need to change very often. Examples include:

  - Chat adapters
  - Twitter credentials
  - Datomic URI
  - etc.

- **Mutable config source** is an `edn` file stored at `./config/mutable.edn` by
  default. `CONFIG_MUTABLE` can optionally be defined to specify a custom
  location. Yetibot reads and writes to this file at runtime.

  A much smaller subset of commands need mutable config, e.g.:

  - IRC channels
  - Room settings

## Prefixes

All immutable config, regardless of the source can be prefixed with either `yb`
or `yetibot`. Examples:

### edn

```clojure
{:yetibot {:log {:level "debug"}}}
```

```clojure
{:yb {:url "yetibot.com"}}
```

### Profile

```clojure
{:prod
 {:env
  {:yb-twitter-consumer-key "foo"}}}
```

### Environment

```bash
YB_GIPHY_KEY="123"
```

### Merged result

If you for some strange reason decided to configure Yetibot through all
available means demonstrated above, the merged config data structure would be:

```clojure
{:log {:level "debug"}
 :url "yetibot.com"
 :twitter {:consumer {:key "foo"}}
 :giphy {:key "123"}}
```
