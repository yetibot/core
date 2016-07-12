# Configuration

See [profiles.sample.clj](profiles.sample.clj) for an example of configuring
Yetibot.

## Modes

Yetibot supports both immutable and mutable configuration.

- **Immutable config sources** include both `profiles.clj` and environmental
  variables (i.e. any source that `environ` can pull from, as all `environ`
  config is immutable). The majority of configurable sub-systems use immutable
  config as they do not need to change very often. Examples include:

  - Chat adapters
  - Twitter credentials
  - Datomic URI
  - etc.

- **Mutable config source** is an `edn` file stored at `./yetibot-config.edn`.
  Yetibot reads and writes to this file at runtime. Only a small subset of
  commands need mutable config:

  - IRC channels
  - Room settings

## Rationale

It's useful to change the configuration of a system at runtime in certain
situtations. It would be burdensome to have to login to a system where Yetibot
runs, change config and restart Yetibot.

On the other hand, the benefits of immutability are well-known. Explicitly
separating out the small amount of mutable config from the majority of immutable
config lets us maximize immutability benefits and minimize negative affects of
mutability in our system.
