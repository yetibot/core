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
#   - using default DB credentials
$ export YETIBOT_DB_URL="postgresql://yetibot:yetibot@localhost:5432/yetibot"
```

### Run the Tests
```bash
$ lein midje
...
>>> Midje summary:
All checks (171) succeeded.

>>> Output from clojure.test tests:

Ran 35 tests containing 57 assertions.
0 failures, 0 errors.
```
