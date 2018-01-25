# Configuration

Yetibot can be fully configured either by environment variables, a config EDN
file, or both. When both are specified, environment variables take precedence;
that is, they override any values set by EDN.

See [profiles.sample.clj](../config/profiles.sample.clj) or
[config.sample.edn](../config/config.sample.edn) for examples of configuring
Yetibot. These are equivalent and both immutable.

## Rationale

Yetibot supports both immutable and mutable configuration, for configuring
different parts of the system.

It's useful to change the configuration of a system at runtime in certain
situations. It would be burdensome to have to login to a system where Yetibot
runs, change config and restart Yetibot.

On the other hand, the benefits of immutability are well-known. Explicitly
separating out the small amount of mutable config from the majority of immutable
config lets us maximize immutability benefits and minimize negative affects of
mutability in our system.

NB: In the future we may move all mutable configuration to the database.

## Modes

- **Immutable config sources** include both `profiles.clj` and environmental
  variables via `environ` and loading EDN from a file at `config/config.edn`
  (this can be overridden with by specifying a `CONFIG_PATH` env var)

  Any config specified in an EDN file will be overridden by values provided by
  `environ`. Environment config can be explicitly ignored by setting an
  environment variable `YETIBOT_ENV_CONFIG_DISABLED=true`.

  Providing config via multiple methods
  makes it compatible with 12-factor configuration and simple usage in container
  environments while still retaining the ease of of use of the EDN file option.

  The majority of configurable sub-systems use immutable config as they do not
  need to change very often. Examples include:

  - Chat adapters
  - Twitter credentials
  - Postgres connection string
  - etc.

- **Mutable config source** is an EDN file stored at `./config/mutable.edn` by
  default. `CONFIG_MUTABLE` can optionally be defined to specify a custom
  location. Yetibot reads and writes to this file at runtime, so it should not
  be modified by hand while Yetibot is running.

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

If you decided to configure Yetibot through all available means demonstrated
above, the merged config data structure would be:

```clojure
{:log {:level "debug"}
 :url "yetibot.com"
 :twitter {:consumer {:key "foo"}}
 :giphy {:key "123"}}
```

Note how environment variables are exploded into nested maps, powered by
[dec](github.com/devth/dec).
