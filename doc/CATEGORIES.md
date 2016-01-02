# Categories

Yetibot commands can be organized under categories. In some cases, commands can
be enabled or disabled according to channel settings (e.g. `:fun`). In the
future, help might rely on categories.

Categories are stored as metadata directly on command handler functions under a
`:yb/cat` prefix with a Set of keywords as the value.

Current known categories are as follows.
Please add to this list as needed. Some categories will overlap but are
semantically distinct.

- `:img` - returns and image url
- `:fun` - generally fun and not work-related
- `:meme` - returns a meme
- `:gif` - returns a gif
- `:ci` - continuous integration
- `:issue` - issue tracker
- `:infra` - infrastructure automation
- `:chart` - returns a chart of some kind
- `:info` - information lookups (e.g. Wikpedia, Wolfram Alpha, Weather)
- `:repl` - language REPL
- `:util` - utilities that help transform or format expressions (e.g. echo, nil,
            buffer) or operate yetibot (reload, update)
- `:broken` - known to be broken, probably due to an API that disappeared
- `:crude` - may return crude, racy and potentially NSFW results (e.g. urban)
