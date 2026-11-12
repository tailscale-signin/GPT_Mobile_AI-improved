# Diagnostic capture 6412928121631152869: remediation and acceptance

## Scope and source

This change implements the supported repairs from the diagnostic audit on PR #819,
`fix/voice-chat-crash-and-diagnostics-2026-11-11`. The source capture is
`app-diagnostics-6412928121631152869.jsonl`, SHA-256
`4c076d5ba224ec4581247676afb6d0b2a3a613564f51382079c0ce74e004340a`,
containing 4,660 records. The capture identifies app commit
`3ee141a8bb8a3b612567550220a9f1209f198673`; it is historical evidence, not an
execution result for this PR's new head. Raw diagnostic contents are not included
in the repository.

Changes were developed in isolated worktrees and integrated over the other
contributors' commits, including `b4118042030a824e22b197408fd3c4e00d3e3774`
`b8ce3e697a3b226b167e590bba1779210c86d02a`, and the browser repair at
`b1fb5b444a572ad3578945677ab7431993faf71d`. Shared checkouts and their
uncommitted changes were not modified. Publication uses a guarded fast-forward
reference update after a fresh remote reconciliation. This work does not authorize merging
the PR.

## Audit disposition

| # | Audited issue | Implemented response and acceptance boundary |
|---|---|---|
| 1 | Historical native exits have no usable native stack in the export | Added bounded Android tombstone-protobuf decoding, crashed-thread frames, program counters, library/build identity, explicit malformed/truncated evidence, and text fallback. PID checks prevent associating a different process. The nearest earlier voice breadcrumb is only temporal context. Historical crash causation still requires a usable stack and reproduction. |
| 2 | Execution-binding rejection loses its typed admission reason | Preserved safe code, stage, HTTP status, retryability, and explicit inference-attempt facts through Gateway and Android. Capability-cache errors retain retryability. Known same-selection binding receipts can be reused while capabilities and revocation remain freshly checked. Admission evidence cannot erase an earlier uncertain command. |
| 3 | Durable primary turns are rejected because client tool isolation is interpreted as no retention | Primary and synthesis requests may retain a visible turn when their explicit role matches the configured role, including the exact GitHub-reasoning alias for an otherwise primary turn. Delegated tool isolation remains intact; reviewer, temporary, and unmatched delegated requests do not gain retention. Server retention continues to honor the explicit request policy. |
| 4 | Saved-job polling lacks an unambiguous terminal/import outcome | Existing interrupted jobs return a terminal receipt even without answer payload. Unknown IDs remain unknown. A pending tool handoff remains continuable when its valid tool calls accompany explanatory prose; failed/cancelled tool payloads do not gain continuation. Android validates terminal/result/continuation fields, records the outcome of each actual recovery read, and transactionally publishes a saved answer once under job/run/chat/ownership fences. Stale current failure controls are archived in history and removed from the current answer. Recovery does not dispatch new inference. Its display projection excludes inline reasoning/control blocks, including legacy raw-result fallback, while preserving answer layout and the unchanged raw evidence. |
| 5 | A 512-token review probe exhausts its output allowance before a verdict | Aligned the review request and stage budget around a preferred 2,048-token allowance, respecting profile hard limits and explicit reasoning policy. One compact same-profile retry is allowed only for a completed output-limit failure. Transport interruption, admission rejection, and unresolved remote outcomes are not retry permission. |
| 6 | Backend text arrives much earlier than the answer becomes visible | Added incremental final-answer delivery through the shared Gateway worker for explicitly supported text-only roles. Late subscribers receive a bounded prefix followed by new text; final content and usage are not duplicated. Leading indentation, tabs, Unicode, and trailing newlines are preserved identically across slow streaming, buffered completion, replay, and saved results. Tool-bearing, structured, reviewer, and bound engineering responses retain their validated completion path. Partial delivery blocks automatic replay. Actual device-visible latency remains to be measured. |
| 7 | Long output and repeated continuation can consume the remaining budget | Preserved the branch's existing suffix deduplication, bounded continuation/regeneration, low-yield stop conditions, and durable partial output. Review verified those guards; this change does not add an unbounded continuation loop or a fallback model. |
| 8 | Repeated send taps while model/Gateway checks are pending produce confusing behavior | Added observable model-check, Gateway-check, and queue-save preparation states. The composer shows preparation and disables duplicate send/workflow actions. Keyboard/accessibility duplicates coalesce. The first tap continues automatically only if draft, profiles, endpoints/credentials, models, tools, reasoning settings, mode, temporary status, selection, and navigation still match. Otherwise the current draft remains. |
| 9 | Self-test metadata consumes budget before fixture prerequisites | Prioritized issue, release/tag, and workflow-job inventories and admissible fixture prerequisites before optional metadata. Fixture mutation still requires the configured approval, supported cleanup, permitted scope, and sufficient lifecycle/verification reserve. |
| 10 | Acceptance totals blur task requirements, capabilities, and proof scope | Added separate task, required-capability, excluded-capability, and inventory counts while preserving historical combined fields. Passed capabilities and safe diagnostic export identify live, retained, or local proof scope. Cached evidence does not imply live transport, device execution, or application-wide acceptance. |
| 11 | Workflow-definition reads revisit cached pages to establish live transport | Live-required reads request live verification on the first eligible read. One successful live receipt can qualify other cached workflow-definition pages only for the same exact tool alias, task binding, repository, authorization revision, and immutable commit. Mutable inventories and fixture existence keep their verification rules. |
| 12 | Nested branch-protection 404 could be mistaken for an unprotected branch | Preserved the existing conservative UNKNOWN policy and added the nested-404 regression. Other policy data or `protected=false` cannot turn an unavailable protection endpoint into merge authorization. |
| 13 | Debug-device frame timings show jank | Reduced repeated diagnostic-list grouping/projection work and retained the existing Markdown coalescing/cache improvements. This is a concrete reduction in repeated application work, not a quantified device-frame improvement. A comparable release/device trace is still required to attribute and qualify the remaining jank. |
| 14 | Live diagnostics repeatedly allocate grouped rows and previews | Reused immutable overlapping log groups, retained previews for the visible page, and released evicted/frozen projection state. Tests cover reuse, updated content, visibility, and eviction. Historical allocation churn is not, by itself, evidence of a retained-memory leak. |
| 15 | Screen-level UI spans lack sufficient component context | Added remembered Transcript, Assistant, and LogRows subtree timings using existing scalar IDs/counts. Sampled operations retain structural owners below the verbose 16 ms threshold. The window stays bounded to 128 samples; context output is capped at 16 entries and 4,096 JSON characters, with explicit truncation, privacy/clear fences, and causalAttribution=false. Device attribution remains to be measured. |
| 16 | MCP configuration, discovery, and stale schemas prevent tools from being admitted | Preserved and reviewed endpoint/credential/configuration revision fencing, explicit reconnect, discovery validation, configuration-error quarantine, stale-schema revalidation, and removed-tool rejection before execution. Invalid service routes, unavailable hosts, and expired credentials still require endpoint-owner or user action. |
| 17 | A selected model reports unavailable readiness while other models work | Preserved exact-model/profile/credential/mode identity, freshness expiry, explicit refresh, and privacy-safe readiness guidance. Closed the chained send-check configuration race. No silent alternate-model dispatch is added; the selected model must actually be loaded on the chosen host. |
| 18 | Recovered metadata EOFs remain unresolved, and raw network records use unrelated identities | Propagated immutable operation/read/attempt identity through coroutine thread switches into real network logging. Explicit full-body completion resolves only the matching incident. Failed attempts, cancellation, response headers, and another read's completion do not. Recovered incidents remain searchable but are excluded from unresolved counts. HTTP and document-validation failures retain their own evidence; this is not a universal terminal receipt for every metadata failure. |
| 19 | Failure reporting loses stage, dispatch, acceptance, or invocation facts | Added typed failure conversion and exact-invocation reconciliation. Stored facts are used only when parent run, requested invocation, stored invocation, and embedded evidence IDs agree. Existing explicit facts and positive dispatch evidence are preserved. An unknown mutation outcome remains uncertain across capability checks and retries; subsequent recovery observes status rather than repeating the POST. |
| 20 | Memory enrichment says it is neither deferred nor waiting while battery constraints block it | Matched WorkManager battery semantics, exposed WAITING_FOR_BATTERY with deferred state and BATTERY_OKAY wake guidance, and retained unknown status when no reliable battery observation exists. Background/UI-hidden trim is classified as lifecycle pressure; release/reload cooldown behavior is unchanged. |
| 21 | Benign inactive input queries and vendor finalizer noise distort incident review | Classified only the known inactive cursor-query warnings as lifecycle noise. Input loss, commit-text errors, fatal events, and vendor finalizer failures remain actionable and grouped. No causal link to voice or the historical native exits is inferred. |

## Safety properties exercised by regressions

- A second send tap cannot start a second turn while admission or durable queue
  insertion is pending. A settings change during either check invalidates the
  original continuation and preserves the draft.
- A typed admission failure cannot turn an already attempted mutation into a
  fresh command. Explicit unknown-outcome codes establish uncertainty, and later
  exception wrapping preserves it monotonically.
- Saved answers are restored under the existing transactional ownership and
  cancellation rules. The current timeline is cleaned without deleting retained
  history, and a repeated result cannot append another revision or republish.
- Incremental delivery uses a single backend request for concurrent subscribers,
  handles a late subscriber's prefix, emits exact final text and usage once, and
  does not retry after partial delivery.
- Live GitHub proof reuse is limited to pinned immutable content under identical
  authority. It cannot qualify another task, tool, repository, credential
  revision, or mutable fixture observation.
- Diagnostic recovery requires exact operation identity and full successful body
  completion. Header-only acceptance and nearby timestamps are insufficient.
- Native evidence is bounded and omits raw command lines, abort-message payloads,
  and process-memory contents. Temporal voice context remains explicitly
  noncausal.

## Validation record

The tested local code candidate is `a2c99b12131b4dd361bf4f263df4bd145b77cb14`.
The published source candidate is `5ce8cb4ad198386415499159af32fb85972eb62a`.
Its complete tree, `0ef45da0924c3095db787183d7637403df97dd5c`, exactly matches
the local publication candidate `4e53a9e6edd1528be3a8c72848d0962653777f0e`,
which adds the regenerated inventories and validation record to the tested code.
A final metadata commit pins the Gateway manifest and this record to that published
source candidate.

Publication used the authenticated GitHub Git data API because the checkout had no
Git push credentials. Every uploaded blob was checked against its local Git SHA
and the assembled tree was checked before creating the source commit. The branch
update requires the expected remote head and disallows force updates.

| Check | Observed result | Scope and limitations |
|---|---|---|
| Canonical host command, `validate-local.sh fast` | Passed: 462 tests; CI policy, schema, catalog, secret scan, regex, resources and diff checks passed | 1,542 literal regex patterns checked, 74 dynamic patterns skipped by that checker; 34 resource XML files checked. |
| Canonical Gateway command, `gateway/check_gateway.py` | Passed: all 64 Python suites; 819 tests, comprising 817 passes and 2 environment skips; browser bridge 7/7 passed | Windows Credential Manager requires Windows, and the executor had no bindable LAN IPv4 interface. Includes the concurrent browser repair and final saved-answer projection. |
| Durable Gateway journal stress | Passed: 50,000 committed events and 782 FULL-sync batches, with restart and eviction/replay assertions | Host SQLite evidence, not Android device or provider latency qualification. |
| Current-source Kotlin compilation and selected JVM/Robolectric tests | Passed: 151 actual production files compiled; 239 tests in 17 suites; no exclusions or skips | All 20 callers of the changed operation/frame context APIs were compiled. Unchanged cached bytecode and generated Room/Hilt implementations/resources were reused. This does not establish fresh KSP generation or a complete Android build. |
| ktlint 1.3.1 | Passed on all 83 Kotlin files changed by this remediation, including generated Kotlin | Source formatting verification. |
| Authentication suites | 32 tests passed in the earlier canonical invocation | Authentication sources are unchanged by subsequent integration. |
| Core-domain Gradle tests | 4 tests passed | Core-domain sources are unchanged by subsequent integration. |
| Canonical full Android validation | Incomplete: Gradle daemon disappeared at `:app:kspDebugKotlin`, before app tests, lint and APK completion | The rerun used the available full JDK 21 and SDK 37. No successful full Android build or current-head CI result is inferred from the focused host run. |
| Physical device and current-head required CI | Not qualified by this local record | Required checks must run on the published head; device acceptance remains separate. |

The machine-readable checkpoint is
`diagnostic-6412928121631152869-task.json`. Its final validation entries identify
the integrated source revision and the checks that completed. Focused test counts
from different runs overlap and must not be summed as unique coverage.

The canonical command is `bash scripts/validate-local.sh full` with JDK 21 and
Android SDK 37. It includes host policy/schema/catalog/secret checks, Python host
tests, Android regex/resources, Gateway suites, authentication suites,
`:core-domain:jvmTest`, `:app:testDebugUnitTest`, `:app:lintDebug`, `assembleDebug`,
and APK runtime/telemetry checks. A stopped or environment-blocked invocation is
not a full passing result. No dependency checksum, Gradle memory limit, or
verification gate is relaxed to obtain a result.

## Remaining acceptance work

1. Run the required GitHub checks on the published head. An older passing run or a
   focused local subset is not current-head merge approval.
2. Reproduce voice startup, recognition, Kokoro playback, and Android TTS fallback
   on the affected physical Android device, retaining build and process identity
   plus a usable native stack if an exit recurs.
3. Measure first-visible-text latency, scrolling/frame timing, and live diagnostic
   heap behavior on a comparable release build and device workload. A real HTTP
   streaming test establishes delivery behavior, not a physical-device latency
   claim.
4. Repair the external MCP/Gateway endpoints and credentials identified by their
   own admission evidence, and load the exact selected model. App-side recovery
   must not substitute another model or bypass those prerequisites.
5. Preserve the existing hosted issue-report service configuration and physical
   QNN/GPU qualification gates. Neither is established by these host tests.

The source export's 118 incident records are not 118 independent defects. Its
historical native exits, user-requested process exit, recovered reads, and repeated
symptoms remain distinct evidence categories. The audit did not establish an ASR,
Kokoro, or Android TTS crash cause, a retained-memory leak, or an observed HTTP 429
incident in that capture.
