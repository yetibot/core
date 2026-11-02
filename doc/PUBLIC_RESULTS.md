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
keys/strings, not serialized JSON byte counts. Views show eight fields/items per
page and announce that large snapshots may be shortened.

Navigation is public and read-only. Components refer to bounded snapshot node
IDs, not arbitrary paths or command arguments. Each view is revision-token
bound and restricted to bot-authored messages in the command's reply
channel/thread. Selecting a field, Back or paging edits only the separate viewer
message. Original answers are preserved. Rerun authorization, confirmation and
replay protection are separate and unchanged.
