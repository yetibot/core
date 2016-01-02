# Load order

Yetibot loads itself in a particular order. The database should be initialized
and schemas loaded as soon as possible, since many models load themselves based
on it. Logging is also saved to db, and we want to start logging as soon as
possible.

## db namespaces

All database schemas should live in `db` namespaces:

- `yetibot.db.*`
- `yetibot.core.db.*`
- `*.plugins.db.*`


The `yetibot.core.db` namespace will map over these, building up their
`schema`s if available.

## logging

`start` fn is called on `yetibot.core.logging` to initialize its database-appender.

## chat adapters

Chat adapters are loaded next. Their `start` functions are called, which
connects to the chat protocol and bootstraps its users into the `users` model.

## observers and commands

Finally, observers and commands are loaded. These typically `require` models and api
namespaces, which may make network or database calls to bootstrap data.
