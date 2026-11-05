# Public command result snapshots

Discord command controls can inspect retained public data without rerunning a
command. Ordinary `:result/data` remains pipeline data; it is **not** a public
export contract.

A trusted command producer must select reviewed public fields explicitly:

```clojure
(require '[yetibot.core.util.public-result :as public-result])

(public-result/reviewed
  {:result/value formatted-answer
   :result/data unchanged-pipeline-data
   :result/public-data {:temperature_c parsed-temperature
                        :source "weather service"}})
```

The in-process metadata marker cannot be forged by serialized user/provider
maps. Merely returning keys called `:result/public-data` or
`:result/public-error` does not opt a result into public inspection. Never mark
arbitrary upstream maps, provider objects or private AI tool data as reviewed.
Use typed JSON-compatible values and fixed field selections, including nested
objects. Credentials, headers, private prompts/reasoning and raw tool
arguments/results are not public fields. Account billing also needs its own
explicit visibility policy.

For approved diagnostics, mark a normal error result and add
`:result/public-error`, for example a fixed service/stage/kind and a validated
numeric HTTP status. Never derive diagnostics by dumping an exception, its
`ex-data`, a request, or a response body. The framework retains only closed
exception/timeout categories and a valid numeric status when available. Discord
shows a safe exception summary; other adapters keep their existing behavior.
If approved details were not retained, the viewer says so.

Data/text inspection follows the **completed outer pipeline**, not its
intermediate stages. Per-stage reviewed error diagnostics are still retained.
The interpreter carries only authenticated public fields in `:public-result`;
ordinary raw `:data` never gains review by passing through a collection command.
A reviewed sequential public projection must match the incoming rows' order and
cardinality. Collection operations select/filter/reorder those companion records
with the same indices, including a single draw for random/shuffle. Duplicate
removal retains the first value's corresponding records; flattening a multiline
value repeats its companion record for each resulting line. `xargs` and `repeat`
aggregate reviewed child projections in output order, leaving unreviewed
positions nil rather than copying raw child results. Unknown transformations or
unaligned/unreviewed input do not publish stale upstream data.

Aliases retain the nested expression's raw data, companion collection and
already-authenticated public projection. They do not review untrusted fields.
`render` preserves those input records and their reviewed projection while
changing only the display value; its `:result/data` is the original data, not
`{:data ... :template ...}`. A nested `weather | render` alias can therefore
flow through `xargs`, sorting and selections without losing weather records.
The handler exposes nested pipeline state only to explicit trusted callers;
ordinary chat responses and history do not retain that private envelope.

The request-local observer does not alter values or data flowing through pipes.
Only bounded copies of approved fields enter the existing command cache;
unsupported objects are unavailable and obvious credential/private field names
are omitted as a secondary safeguard, not the primary privacy boundary.
Snapshots freeze at completion, reject late updates, and expire with command
edits/cache eviction/restarts. Live replies gain controls at terminal
reconciliation, after their snapshot is frozen.

Current limits: eight data records and eight diagnostic records; six nested
levels below the root, 32 fields/items per container, 1,200 UTF-16 characters per
string. Data has a 192-value/8,000-character budget; diagnostics reserve a
separate 64-value/4,000-character budget. Character budgets apply to retained
keys/strings, not serialized JSON byte counts. Large snapshots may be shortened
when retained; opening a view never refetches missing data.

A producer may additionally opt its already-redacted, human-readable display
into text paging with `:result/public-text` under the same reviewed marker.
Never put raw catalogs, schemas, provider maps or private tool output there.
The Discord adapter only enables paging when this text exactly matches the
final Markdown attachment; transformations in a pipeline do not resurrect an
earlier listing. One complete public document of at most 262,144 UTF-16 units
is retained separately from the bounded data tree. Larger documents retain the
ordinary complete Markdown fallback; they are not silently truncated into pages.
Pages contain at most 3,000 UTF-16 units, prefer line boundaries and never split
surrogate pairs. Markdown code fences are closed at page boundaries and reopened
on continuation pages; these presentation-only markers never alter the stored
text or Markdown download. Fence markers longer than 64 characters retain the
ordinary download fallback so synthetic markers cannot exceed the message
budget. Previous/Next update only the tracked original reply, keeping
its file, controls and single original elapsed caption. Bounds are disabled and
a page indicator remains visible. There is no rediscovery or execution during
navigation. Opening another pane uses the first page as its restoration base;
paging returns to the frozen document and replaces the temporary pane.

Navigation is public and read-only. Controls select bounded snapshot depth,
page or diagnostic node IDs, never arbitrary paths or command arguments. Each
inline view requires the actual tracked bot reply, channel/thread, frozen
snapshot token and current revision.

**Explore data** expands inside the original reply and becomes **Hide data**.
It initially shows two levels (or all available levels for shallower data).
**Expand** shows one more level everywhere; **Collapse** shows one fewer level
and both reset to the first page. Collapsed objects/arrays show type/count
summaries. Scalars retain their JSON types. The JSON preview is paged in
surrogate-safe chunks of at most 1,500 UTF-16 characters, preferably at line
boundaries; a large JSON document can continue across pages. Previous/Next
preserve depth. Depth and paging buttons are disabled at their bounds. There
is no one-field dropdown or separately posted viewer. Hiding restores the exact
original card; reopening starts at the two-level overview.

**Error details** expands the tracked original reply in place. Its navigation
also stays inline, and **Hide error details** restores the exact original card.
Only JSON-only outbound public presentation is retained for restoration, never
upload streams or raw result data. Restoration is capped at the ordinary
30-reply limit; overflow notices do not offer unrestorable inline controls.
Attachments are neither uploaded again nor cleared, and one original elapsed
caption is preserved. Long error text may be
shortened only while details are open; collapsing restores it in full.

**Show details** expands the frozen, caller-sanitized tool progress summary
inside the same reply and becomes **Hide details**. Hiding restores the exact
original card, including media/files and the original elapsed caption. Details
are available after terminal reconciliation, not during live answer updates.
The summary retains only the existing bounded status text (at most 1,800 UTF-16
characters), never raw tool arguments/results, private reasoning or provider
responses. Late status writes do not alter an already-published summary.

Data, tool and error panes share the same JSON-only restoration base and
30-reply limit; opening one replaces the other pane. Long answers may be
shortened only while a pane is open; Hide restores their original presentation.
No opening, hiding, depth or paging action executes a command, tool or upstream
request. Data navigation is stateless: its tokenized controls encode only a
bounded depth and page, not mutable paths or new copies of result data.

Inline updates additionally require the actual tracked reply ID, matching
snapshot token and current revision. Updates serialize with reply replacement,
recheck ownership immediately before IO and never retry an unknown outcome.
Rerun authorization, confirmation and replay protection remain unchanged.
