# Repository map reliability and open report follow-up — November 14, 2026

## Scope and revision boundary

All work continues on PR #1122, branch
`fix/github-context-and-voice-reliability-2026-11-14`, against main
`5d4b4d81c121b36cf01b79fe8162fb78f793f203`. The map audit inspected
`1987683f8b560c6e1bae0d2a8ea22344980cdf9d`; implementation started after
reconciling `6d56939a5811c7779e49a072d8d1e3c08cf8fdd3`. The subsequent
diagnostic and Windows-policy repairs through
`2f7d5826c4ab043c628fae5f54caf32cdd8349d3` were incorporated by fast-forward.
Concurrent voice/report and workflow corrections through
`90c2f835c032c3fce846777a07265fccb83fd43b` were then incorporated on the
same branch, preserving both sides of the overlapping report allowlists.
No existing commit was replaced.

The fresh remote inventory contains 748 branches and one open PR. The preceding
branch review records 384 tips included in main, the active repair branch, and
363 other historical or fixture branches. Recent repair branches are already
included in main. Historical branches remain intact; this is not a claim that
every old branch independently builds. Current fixes and qualification belong
to the single active repair branch.

The issue intake contains 27 open reports: #1101–#1128, excluding PR #1122.
Their preceding fixes and diagnostic dispositions are recorded in
[the combined chat, voice, and diagnostic repair](github-chat-motion-voice-2026-11-14.md).
This follow-up addresses the repository-map audit and strengthens the new
integration and policy-status reports #1127 and #1128. Issue closure follows
qualification and merge of the combined current head.

## Observed problems and implemented changes

| Audit finding | Implemented correction | Evidence and limits |
| --- | --- | --- |
| Architecture responses exceeded the 32 KB UI budget; dictionary keys alone could exceed 280 KB. | Architecture and query responses use revision-bound, scope-bound pages with explicit continuation cursors, counts, and complete edge endpoints. Budget failures retain revision identity and a typed cause. | A 3,200-file fixture traverses all files and 3,199 relationships through bounded pages. An individual result too large for its budget stays explicit. |
| The first planner invoked recall before storing the freshly observed GitHub actor and head SHA. | Store those observations before model planning; stage completion remains the supervisor's responsibility. | The first planning request is tested with the observed actor and immutable head. |
| Files above 256 KiB disappeared from structural coverage. | Parse source up to 2 MiB with bounded excerpts; retain file identity and an explicit limitation for larger or non-UTF-8 files. | Large-file tests check declarations beyond the former cutoff. Coverage does not imply compiler-resolved semantics. |
| Common Kotlin package calls, import aliases, Python module aliases, and JavaScript/TypeScript arrows were missing. | Resolve supported static relationships using packages and import bindings; extract arrow declarations. | Positive and ambiguous/negative regressions preserve unresolved cases instead of guessing. The map screen's own cross-file call is found. |
| XML, YAML, TOML, native source, and headers were absent. | Include bounded structural or configuration metadata, with explicit parser and coverage limits. | Metadata visibility does not claim execution or full native call-graph coverage. |
| A map built through the public reader was not reused for authenticated recall. | Reuse unchanged exact-revision fragments only after verifying public repository visibility and the current account/repository scope. | Tests prohibit private-repository reuse and mismatched account/revision reuse. |
| A visible map suggested that every selected model automatically received its contents. | Show selected-model eligibility separately from map availability, retain the existing local-model boundary, and emit content-free retrieval/admission receipts. | A visible map is not proof of model consumption. Receipts distinguish retrieval, admission, and dispatch. |
| Objective-only lexical selection favored generic documentation. | Rank production source, symbol names, paths, and relationship context; select stage-specific inspected or changed paths. | Ranking fixtures and source-wide observations are separate from model-answer accuracy. |
| General evidence compaction could discard all graph context. | Reserve a bounded graph allocation and keep exact source anchors, complete visible relationships, and explicit omission counts. | Context tests inspect the actual admitted request; no alternate model or unbounded context is substituted. |
| The search field filtered only visible file nodes, while pagination hid relationships. | Search backend symbols, request backend result pages, retain navigation history and neighborhood focus, and report external relationships separately. | UI and repository-layer tests cover page identity, scope changes, and source selectors. |
| Processing finished with failed files but the UI could not retry them. | Expose explicit failed-file retry independently of pending-file count; distinguish processing completion from coverage completeness. | Retry tests ensure failures can be retried without pretending unsupported files are repaired. |
| Screen lifetime, a 90-second request window, and 32 pages stopped larger indexing operations. | Add encrypted durable indexing jobs with bounded batches, lease recovery, idempotent request acknowledgments, explicit stop controls, and status observation independent of the screen. | Worker tests cover real source batches, restart recovery, access revocation, cancellation, and lost acknowledgments. A closed screen detaches its observer. |
| Queries rebuilt relationships and partial revisions discarded reusable fragments. | Cache resolved graphs by scope/revision/fragment identity and reuse unchanged fragments. Keep status separate from relationship resolution. | Tests invalidate changed fragments and scope and distinguish unmeasured relationship counts from zero. |
| The self-test only read status and reported generic missing inputs. | Prepare the selected exact-revision index, reuse observed graph selectors, verify task-bound prerequisites, and retain precise graph and policy blockers. | Read-only self-test operations do not invent write approval or promote unobserved checks. |

## Durable indexing contract

The UI requests background indexing only when the gateway advertises
`durable_index_jobs`. Synchronous repository-graph tool calls retain their
existing behavior. Old gateways continue to use bounded synchronous batches.

The saved job is scoped to gateway principal, GitHub actor, repository, branch,
and immutable commit. Every batch rechecks current authorization. Source reads
remain read-only. Job payloads are encrypted with the existing engineering key;
loss of that key does not silently replace it over saved encrypted jobs.

Each client request has an acknowledgment identity. Repeated requests reconcile
the same job instead of restarting it. A worker lease prevents an expired worker
from overwriting a replacement's outcome. Stop, pause, and resume require the
observed job generation; an older map screen cannot cancel a newer generation.
Resuming an exhausted budget renews its bounded allowance. Error reports retain
fixed diagnostic codes rather than exception bodies or source text.

`COMPLETED` means the queued source processing has finished. The separate
`processing_complete`, `coverage_complete`, failed, unsupported, and partial
counts preserve incomplete coverage. UI refresh and model retrieval cannot turn
those counts into proof that all runtime relationships are known.

A new explicit request can rebuild a snapshot that was evicted or invalidated
after a historical completion. A lost failed-file retry response is reconciled
against the retained source-processing summary. An unchanged old completion
leaves `INDEX_RETRY_OUTCOME_UNKNOWN`; it does not authorize a replay or establish
that the retry succeeded. Cancellation also reaches the active source batch and
prevents it from publishing a new snapshot after its job has been stopped.

## Source-wide observations

The local reconstruction used `2f7d5826c4ab043c628fae5f54caf32cdd8349d3` plus
the uncommitted repairs. These are working-tree source observations, not a
deployed gateway or model-answer benchmark:

| Observation | Result |
| --- | --- |
| Eligible / processed files | 5,555 / 5,555 |
| Fully indexed / partial files | 5,456 / 99 |
| Excluded files | 212 |
| Serialized snapshot | 113,461,662 bytes; saved successfully under the existing 128 MiB bound |
| First architecture page requested with 32,000-byte budget | `FOUND`, 10,106 bytes, 12 nodes, 8 relationships, more pages available |
| Encrypted summary read on this host | 0.0017 seconds; excludes network authorization |
| `ChatViewModel.kt` | 227 nodes, indexed |
| `ChatRepositoryImpl.kt` | 77 nodes, partial; its limitation remains visible |
| `gateway_v13.py` | 321 nodes, indexed |

The voice-pronunciation query now selects production voice files including
`VoiceSpeechText.kt` and `KokoroSpeechText.kt` among its initial candidates.
This establishes more relevant source retrieval for the inspected example;
it does not establish a percentage improvement in model accuracy or token use.
Processing is complete in this reconstruction, while coverage remains partial.
Encrypted status summaries are atomically paired with snapshot revision,
storage version, and extractor version, avoiding full snapshot decryption and
relationship reconstruction during ordinary status polling.

## Reporting and unresolved environments

Public graph retrieval receipts contain only allowlisted states, counts, timing,
revision-match booleans, and source digests. They exclude source text, paths,
repository names, raw diagnostic excerpts, and exception messages. They measure
the admitted model request and retain whether it was dispatched.

The new graph and policy report vocabulary is negotiated through
`GITHUB_GRAPH_POLICY_EVIDENCE_V1`. A hosted reporter that lacks this feature
does not receive an incompatible payload; the approved report remains saved.

Reports #1127 and #1128 need different diagnoses. The integration report showed
unattempted graph operations after an initial `NOT_INDEXED` result. The policy
report showed an inaccessible legacy protection endpoint alongside observed
rules. The follow-up records precise graph prerequisites and endpoint-specific
HTTP evidence; a missing or forbidden endpoint remains unknown. It is not
treated as proof that no policy exists or as authority to bypass it.

Existing voice and provider reports without a reproduced underlying cause use
the user's requested diagnostic-follow-up disposition. Bounded retry behavior
and additional evidence are described explicitly in each closure. Phone audio
ownership, private-provider behavior, matching app/gateway deployment, and a
compatible hosted report service require their own execution evidence.

## Additional CI repair

Marketplace workflow run `43621179565` failed during
`:app:kaptGenerateStubsDebugKotlin` with `OutOfMemoryError: Java heap space`.
Its Room and annotation generation previously shared a 3 GiB process. The
workflow now separates those stages and uses the canonical PR workflow's bounded
4 GiB annotation-processing configuration, retaining one worker, independent
timeouts, and uploaded diagnostics. No test or required gate is skipped.

## Qualification

The machine-readable checkpoint is
[repository-map-reliability-2026-11-14-task.json](repository-map-reliability-2026-11-14-task.json).
The final local canonical host run passed 508 tests and resource/schema checks.
The canonical authentication modules passed 99 tests. The planner/report host
passed 178 cases, and the separate map/data host passed 31. Those Kotlin hosts
use explicit platform substitutes. Durable-worker behavior and real-service
integration passed 19 cases; the continuity suite passed 24 after repairing a
disabled-storage shutdown failure found by the full gateway run. The full
gateway gate is rerun on that correction; hosted qualification remains bound
to the subsequently published head. Detailed final outcomes belong to the PR's
exact-head check records and completion comments.

Focused behavior tests precede generated-inventory refresh, canonical host
validation, the complete gateway suite, reporter contracts, Kotlin formatting,
and hosted Android qualification. Early host checks correctly reject stale
generated inventories while source work is in progress; the inventories must be
regenerated from the final source.

The effective main ruleset `27005122` requires `Required verification` and
`Analyze (java-kotlin)`, with a current base and no configured bypass actors.
Merge must use the expected published head after its required checks pass.
Cancelled, missing, stale, or skipped required checks are not passing evidence.

Physical-device outcomes and model-answer accuracy/token savings remain
unmeasured unless separately recorded. Structural fixtures, host compilation
with substituted platform boundaries, full Android builds, and device execution
are distinct qualification levels.
