# Testing

## Philosophy

Tests should allow for a typical REPL driven workflow, just like typical development. This means every fact should be idempotent and able to be exercised in isolation, apart from other facts in a given test namespace.

Database setup can occur but as an alternative, consider mocking out side effectual functions.

All new tests should use Midje. Some old tests remain that still need to be ported.

## Up & Running

### Setup Development DB
```bash
# assumes in root directory
$ docker-compose up -d
```

### Expose Database Connection
```bash
# assumes:
#   - in root directory
#   - using default DB credentials as defined in docker-compose.yml
$ export YETIBOT_DB_URL="postgresql://yetibot:yetibot@localhost:5432/yetibot"
```

### Run the Tests
```bash
$ lein test --help
test is an alias, expands to ["with-profile" "+test" "midje"]

$ lein test
...
>>> Midje summary:
All checks (171) succeeded.

>>> Output from clojure.test tests:

Ran 35 tests containing 57 assertions.
0 failures, 0 errors.
```

#### Run Targeted Tests
```bash
# run tests for a specific NS
$ lein test yetibot.core.test.config

# run tests for a group of similar NS'es
$ lein test yetibot.core.test.util.*
```

### Testing Extra Credit

#### Kondo-fy Code

Using [`clj-kondo`](https://github.com/clj-kondo/clj-kondo), lint the modified code.
```bash
$ clj-kondo --lint src/yetibot/core/db/util.clj
$ clj-kondo --lint test/yetibot/core/test/db/util.clj
```

#### Check Your Coverage

Using [`cloverage`](https://github.com/cloverage/cloverage), check your code coverage.
```bash
$ lein cloverage
```

#### Check for Climate Change

Using [`codeclimate`](https://github.com/codeclimate/codeclimate), review the modified code.
```bash
$ codeclimate analyze src/ test/
```

### REPL Help

#### Loading the Sample Config File

Before you start the REPL (`lein repl`), if you are working in isolation (not connected to external systems, except maybe the DB) and want to test reliances on project configs (i.e. admin commands/users, command prefixes/fallbacks, logging level/path, etc) - it's not a bad idea to load the sample environment config file and export its variables. For example:
```bash
# assumes you are in the project root directory
$ source config/sample.env

$ export $(cut -d "=" -f 1 config/sample.env | grep '^YETIBOT_')

$ env | grep YETIBOT_ | sort
YETIBOT_ADAPTERS_FREENODE_HOST=chat.freenode.net
YETIBOT_ADAPTERS_FREENODE_PORT=7070
YETIBOT_ADAPTERS_FREENODE_SSL=true
YETIBOT_ADAPTERS_FREENODE_TYPE=irc
...

$ lein repl
```
