# Durable AI usage capture

Normal Yetibot runtimes reserve an anonymous PostgreSQL ledger row before each
supported inference boundary. A request cannot proceed if that initial write
fails or does not acknowledge an inserted row. Catalog reads, operation status
polls and artifact downloads are not additional inference events.

## Startup and API

`init/-main` makes tracking mandatory before opening nREPL. Database startup
creates the additive `ai_usage` schema, then `ai-usage/start!` creates the
idempotent coverage marker and timestamp index before adapters and background
command loading activate. Interrupted Gemini agents wait for both the database
and a connected adapter. Losing the database's connected flag later cannot
silently disable capture.

Standalone library helpers used without starting Yetibot or its database keep
their previous API and are outside this runtime coverage. There is no chat
command or runtime setting to disable a running bot's required ledger.

`yetibot.core.util.ai-usage/capture!` accepts host-owned attributes, a request
function and a metrics projection. It:

1. Commits the pending reservation before invoking the request function.
2. Binds anonymous context/request UUIDs for nested model calls.
3. Finalizes safe counters and money in a separate transaction.
4. Returns the original response, even if final ledger preparation or storage
   fails. The durable pending row then remains uncertain.

It never retries inference to repair accounting. A request exception retains
its original behavior; only a bounded HTTP status and failed/unknown state are
recorded. Existing model validation, media budgets and delivery behavior are
not replaced by the ledger.

Write transactions require synchronous commit. Connections and transactions
have bounded login, socket, statement and lock waits; no transaction remains
open while a provider or CLI is running. Finalization updates pending rows only.

The four-argument handler API is unchanged. It supplies an anonymous accounting
context around command execution. Applications entering outside that handler,
such as literal reply continuations, must establish the same context binding.

## Captured boundaries

- xAI text and image generation, including image-prompt rewriting.
- Gemini image generation and one Veo generation, not its polls/downloads.
- Gemini CLI process sessions, reserved before process spawn.
- Application Chat Completions, Responses, streams and each native-tool round.
- Application delegated model calls, structured classifiers and legacy OpenAI.

Each actual application wire turn is recorded independently. Do not insert a
parent caption's accumulated cost as another request receipt.

## Stored data

`ai_usage` contains anonymous event/context/parent UUIDs, event kind,
provider/model labels, operation, timestamps, state, safe HTTP status and a
closed per-model JSONB metrics array. It has no prompt, completion, reasoning
text, tool arguments/results, credential or user/channel identity fields.
Labels reject URLs, known credential forms and supplied configured secrets.
Metrics are bounded nonnegative native counters and finite amounts; absent or
invalid counters/prices remain absent rather than invented zeroes.

HTTP events distinguish a reserved attempt from a known receipt. CLI events are
sessions with up to 32 native model summaries. Actual CLI request counts are
kept when available; missing summaries do not become one request per session.
CLI family-based budget estimates are not treated as dollar bills.

The coverage sentinel is excluded from usage totals. Its original timestamp is
retained across restarts. It describes rollout, not historical reconstruction
or proof of provider-account completeness.

## Aggregation

`db.ai-usage/report!` uses one read-only repeatable-read snapshot, with an
inclusive start and exclusive end. PostgreSQL timestamp precision is accounted
for without changing caller comparisons at nanosecond boundaries.

Global event/session counts are distinct across expanded per-model summaries.
A multi-model CLI session can appear in several groups; those group counts
must not be added as unique sessions. Global totals include all groups even
when the displayed breakdown is limited to 200. Integral counters stay exact;
dollar amounts stay decimal.

Reported USD, estimated USD and unpriced events are separate. Native token
counter definitions can differ by provider, and cached/reasoning counters can
overlap other counters. These are recorded subtotals, not account invoices,
account entitlements or usage from other applications. Opaque external tool
services are not observable unless included in provider/CLI statistics.

The application supplies the trusted-human report command and reviewed frozen
public projection. See its `doc/AI_USAGE.md` for syntax and report semantics.
