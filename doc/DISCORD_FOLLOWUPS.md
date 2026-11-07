# Host-owned Discord AI continuations

The application may opt a successful standalone `ai`, `grok` or `zen` answer
into plain-reply continuation. This is not sticky channel routing or a command
string to replay. The adapter stores only closed routing metadata in its
existing 1,000-entry command cache, alongside revision ownership and actual
Discord reply IDs. There is no additional conversation-text session store.

## Application contract

`remember-command-continuation!` accepts `:command`, `:provider`, `:model` and a
64-character lowercase `:provider-signature`, plus optional boolean `:no-tools`
and `:verified-free`, bounded `:toolset`, integer `:max-tool-calls` (1–128)
and integer `:max-output-tokens` (1–262144). These host-owned effective budgets
are pinned routing options, never parsed from literal reply text.
Unknown fields, callbacks, prompts, credentials and executable text are not
accepted. It returns `:accepted`, `:stale` or `:unavailable`; invalid metadata
throws a static `IllegalArgumentException`. Applications must verify successful
standalone AI provenance before calling it; this is trusted host code, not an
EDN/API capability or a model tool.

Admission waits for successful terminal delivery and a non-error inspection
state. Working cards, partial/live answers before completion, uncertain sends
and error cards do not become eligible parents. Cache eviction, source edits,
message deletion and process restart invalidate ownership. An eligible reply
must name a prior tracked answer in its own channel, with a normal reference.
Bot authors are ignored. Explicit prefixed commands override the inherited
route; embedded-looking syntax inside an admitted plain reply remains literal.
Duplicate admitted gateway creates do not repeat a turn.

During a trusted active follow-up, `command-followup` returns its route.
It returns nil for an ordinary command and `:stale` if follow-up ownership was
lost. Applications must fail closed on `:stale`, never fall back to parsing the
human body. Recheck after reading context and before inference. Dispatch the
literal prompt through the chosen AI entrypoint, re-resolve provider identity,
check the current actor/tool policy and retain existing context limits. A
verified-free route must reverify the same model, never silently rotate or
become paid. Explicit paid follow-ups are ordinary new paid requests, with no
fallback or automatic failure retry.

Native command replies, no-ping controls, frozen public-result inspection and
requester-only rerun confirmation retain their existing ownership and transport
rules. Routing metadata is private host state, never an Explore data snapshot.
