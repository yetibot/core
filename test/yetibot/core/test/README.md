# Testing

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
