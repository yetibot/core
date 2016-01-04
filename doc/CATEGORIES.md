# Categories

Yetibot commands can be organized under categories. In some cases, commands can
be enabled or disabled according to channel settings (e.g. `:fun`). In the
future, help might rely on categories.

Categories are stored as metadata directly on command handler functions under a
`:yb/cat` prefix with a Set of keywords as the value.

Current known categories are as follows.
Please add to this list as needed. Some categories will overlap but are
semantically distinct.

See the [category command
code](https://github.com/devth/yetibot.core/blob/12130a130d6739774bdbc442eb9ed37e721d7afd/src/yetibot/core/commands/category.clj#L10-L25)
for the most up-to-date reference. Pasted here for reference:

```clojure
{:img "returns an image url"
 :fun "generally fun and not work-related"
 :meme "returns a meme"
 :gif "returns a gif"
 :ci "continuous integration"
 :issue "issue tracker"
 :infra "infrastructure automation"
 :chart "returns a chart of some kind"
 :info "information lookups (e.g. wiki, wolfram, weather)"
 :repl "language REPLs"
 :util "utilities that help transform expressions or operate Yetibot"
 :crude "may return crude, racy and potentially NSFW results (e.g. urban)"
 :broken "known to be broken, probably due to an API that disappeared"}
```

## Channel-based category toggle

Each category can be disabled or enabled at the channel level. By default all
categories are enabled. To disable them, use `!disable :category-name`. 

> n.b. disabled categories are stored using the normal channel settings, so
> you'll see them in `!room` if you set them. `!category` is merely a
> convenience wrapper.

Show the list of categories and their docs:

```
!category
```

Disable the "fun" category:

```
!disable fun
!category dis fun
```

Show disabled categories:

```
!disable
```

