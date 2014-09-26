# Command handling pipeline

This describes an overview of how yetibot takes raw input and passes it through
its command handling pipeline.

## Raw input

`yetibot.core.handler/handle-raw` is passed params from an adapter:

- `chat-source` e.g. `{:adapter :irc :room "#clojure"}`
- `user` e.g. `{:last-active #<DateTime 2014-07-16T17:28:59.363Z>, :name devth, :username devth, :id ~devth, :user ~devth, :nick devth}`
- `event-type` one of: `:message`, `:leave`, `:enter`, `:sound`, `:kick`
- `body`–this is the actual text the user wrote. Only `event-type`s of
  `:message` have a `body`. Otherwise it is `nil`. When not `nil`, `handle-raw`
  checks to see if the `body` is prefixed with `!`. If it is, `handle-raw`
  strips the leading `!` and `yetibot.core.handler/handle-unprased-expr` is
  called with `chat-source`, `user`, and `body`.

## Unparsed expression handling

Raw, unparsed expression are passed to
`yetibot.core.handler/handle-unprased-expr`, along with the `user` and `body`.
`handle-unprased-expr` sets a `binding` for the latter 2, then parses and
evaluates the expression using `yetibot.core.parser/parse-and-eval`.

## Parsing

`yetibot.core.parser` is an
[Instaparse](https://github.com/Engelberg/instaparse) parser, which fully
specifies the yetibot grammar, including arbitrarily-nested subexpressions,
piped commands and literals. Once a raw expression is parsed into an AST, it is
evaluated using Instaparse's `transform` helper, which takes a map of AST keys
to functions. The important function here is
`yetibot.core.interpreter/handle-expr` which takes any number of individual
commands to be reduced using pipe semantics.

## Evaluating

`handle-expr` literally `reduce`s its `cmds` arguments over the
`yetibot.core.interpreter/pipe-cmds`. Each command is evaluated from left to
right, and each evaluation output is passed as arguments to the next command.
Command output can either be a single value, or a collection of values. This
affects the method `pipe-cmds` will pass it to the next comand:

- **Single value**: `pipe-cmds` will simply append it using
  `yetibot.core.util/psuedo-format`, which is similar to `clojure.core/format`
  except it will append the value at the end of the string if there is not a
  `%s` placeholder present, and if there are *multiple* `%s`s present, it will
  replace *all* of them with the value.
- **Collection**: `pipe-cmds` passes the collection as an optional `:opts` key
  in the `extra` argument to `yetibot.core.interpreter/handle-cmd`, letting
  individual commands do whatever they like with the `:opts`. For example, many
  of the `yetibot.core.commands.collections` commands require a `:opts` key as
  they primarily operate on collections.

`yetibot.core.interpreter/handle-cmd` is the function that gets
[hooked](https://github.com/technomancy/robert-hooke/) by `yetibot.core.hooks`.
Each command in the `yetibot.core.interpreter/hooks` collection gets to look at
the args and decide whether it can handle the input or not. As soon as a single
command handles it, evaluation is complete. If no command handles it,
`handle-cmd` will fallback on google image search.
