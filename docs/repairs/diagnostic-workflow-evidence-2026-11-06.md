# November 6 diagnostic workflow and evidence repair

Repository: `tailscale-signin/GPT_Mobile_AI-improved`

Base: `main` at `e1c85e10370d0e40f80e68c6875656117fdc5ffa`

Branch: `fix/diagnostic-workflow-evidence-2026-11-06`

The additive review against merge build `69309b332c0d25b6721a2d7befbc04f49b804a3b` is covered by the [gateway, task verification and retention follow-up](gateway-task-retention-2026-11-06.md). Both reviews are implemented on this branch before merge.

## Scope and source findings

The supplied review describes GPT Mobile AI 0.9.66.5, build 139, at the exact base commit above. The review was available during this implementation; the original diagnostic ZIP was not attached to this turn. Its counts are observations from retained evidence, not independently reproduced session totals.

The source already contains a durable GitHub evidence book, acceptance ledger, task recovery, bounded replay, paged database capture, staged diagnostic archives, and a frame-pressure guard. Replacing those foundations would introduce competing completion rules. This patch repairs their integration and extends the existing contracts.

The principal confirmed source defect was the checklist's repeated traversal of local display fragments even after the complete original response had been retained as verifier evidence. This spent the same authorized call budget needed for other capabilities, fixture cleanup and final verification. The repair reuses the trusted original together with its exact remote continuation recipes. Actual remote pages remain required; a saved local display page is not a new remote observation.

Additional confirmed defects include a stale live invocation status during terminal event delivery; repeated serialization and measurement of oversized immutable tool results; a bounded journal snapshot being used as an apparently complete export; message export using a single-record size ceiling as a source-record ceiling; and MCP compatibility and discovery handling that could repeat definitive failures.

## Disposition of all 24 numbered findings

| # | Finding | Implementation and acceptance boundary |
|---|---|---|
| 1 | Three incomplete run endings | The existing encrypted evidence book and task recovery remain authoritative. Checklist v3 saves unfinished capabilities, cleanup priority, budget state and exact continuation recipes. Completed mutations are retained; uncertain writes require reconciliation. Historical runs are not relabeled as successful. |
| 2 | Verification is the failure stage | `GitHubVerifiedSession` retains an evidence-linked acceptance checkpoint before publication. The existing `GitHubAcceptanceLedger` separates capability results from required product outcomes. Missing evidence continues to produce a partial outcome. |
| 3 | Only 100 receipts in the export | `DiagnosticDatabaseSource` exports every retained receipt up to a frozen row-id boundary in pages of 16, without a total row limit. New coverage metadata records expected, inspected and emitted rows. Records already deleted cannot be recovered. |
| 4 | 100/100 tool calls used | `GitHubChecklistBudget` obtains the current remaining execution budget, reserves verification capacity and requires enough calls to finish a disposable fixture before creating it. Existing cleanup takes priority over optional reads. The authorized hard ceiling is unchanged. |
| 5 | Cached operations resemble live tests | `GitHubEvidenceOrigin` records `LIVE`, `CACHED`, `LOCAL`, `MOCKED` or `NOT_TESTED` independently of dispatch and response validation. An exact authorized read may request one explicit `verify_live` refresh; mutation tools reject this option. Cached observations cannot establish live transport qualification. |
| 6 | Receipts are dominated by context reads | Checklist continuation skips local display fragments only when the trusted receipt confirms the complete original and its continuation recipes were retained. The schema-derived capability matrix remains the coverage authority; unexecuted capabilities remain visible. |
| 7 | Accepted mutations do not prove final state | The owned disposable-branch fixture now requires confirmed deletion followed by a remote absence check. Creation, inspection, cleanup and cleanup verification remain separate receipt-linked states. A successful HTTP mutation alone does not satisfy cleanup. |
| 8 | Repeated repository context retrieval | Complete saved originals and repository snapshots are reused. Exact remote page recipes are retained privately for the verifier, rather than injected into every model-facing result envelope. Explicit live verification bypasses both HTTP and CI-log read caches. |
| 9 | Tool completion and task completion differ | The existing authoritative task verdict remains distinct from transport completion. Checklist v3 adds provenance, response-validation and fixture-state fields without introducing a second run state machine or converting successful receipts into task success. |
| 10 | Partial runs are retryable | Resume plans contain unfinished capabilities and pending exact reads. Cleanup is selected first. Failed or uncertain writes are never replayed to recover a response, and cache-refresh attempts are recorded so a restart does not repeat them indefinitely. |
| 11 | 102 replay compactions | `ToolExchangeCompactor.RenderCache` retains bounded per-result measurements and digests through weak identity keys, including for large bodies that cannot fit its serialized-body cache. Discarded large source bodies are not pinned by the cache. `AgentRunner` reuses those measurements for admission and compaction. |
| 12 | 618,994 estimated tokens compact to 10,849 | Complete durable evidence remains separate from the bounded model projection. Serialization is deferred until a result is selected for the replay allowance. Compaction does not delete the saved source or invent missing evidence. |
| 13 | Replay repeatedly approaches its budget | Existing hard replay limits, newest-result preservation and new-observation capacity remain enforced. Cache reuse changes computation cost, not the allowed context or the accepted execution budget. Ordinary GitHub facade schemas retain their existing below-1,000 estimated-token gate; live-refresh controls are limited to remote facades and repeated wording is removed. Regressions compare cached and uncached results at several budgets. |
| 14 | Usage is estimated | Existing provider usage counters retain their measured or estimated provenance. Live invocation observations now publish actual provider counts when available, together with the same evidence used for final persistence. Providers that supply no counters remain explicitly estimated. |
| 15 | Allowances resemble consumption | Live and final `usageEvidenceJson` separate input/output reservations, observed counters, final-usage completeness and confirmed cost. Requested output capacity is never represented as measured output consumption. |
| 16 | Invocation is RUNNING while the run is PARTIAL | `InvocationLedger` publishes its observed terminal status before forwarding the terminal event downstream. The export timeline correlates parent and invocation observations and flags unresolved lifecycle differences; independently captured timestamps are not treated as proof of a stuck request. |
| 17 | Journal sequence gaps | `RunDiagnosticJournal` retains per-stream durable high-watermarks, packs vault batches by encoded bytes and event count, and streams all retained batches at an export boundary. `RunJournalSequenceCoverage` detects missing intervals without retaining every event body. Unreadable batches, unknown legacy tails and retention pruning remain explicit. |
| 18 | A 147,734-byte message exceeds the record limit | Bounded source records are redacted in full, then emitted as `diagnostic_record_chunk` envelopes. Each includes ordering, UTF-16 offset, chunk SHA-256 and full-record SHA-256. UTF-8 hashes and surrogate-safe boundaries permit independent reassembly. Oversized source records still receive an explicit omission. |
| 19 | Runtime incidents are empty | `LocalRuntimeIncidentHistory.capture()` returns collection status and availability metadata separately from incident evidence. No retained incidents, unavailable capture and failed/partial capture have distinct outcomes. Metadata does not create a fictitious incident. |
| 20 | Optional sections are empty | Stage receipts record availability, collection completion, evidence count and omission reasons. Database sections add table availability and row-boundary coverage. Empty retained data does not imply unavailable capture. |
| 21 | Sections have independent timestamps | The manifest explicitly describes an interval with per-stage timestamps and frozen row/sequence boundaries. Resumed sections retain their capture times. This is not presented as a database-wide transaction snapshot. |
| 22 | Evidence is spread across sections | `timeline.jsonl` normalizes bounded run, tool, invocation and journal observations using source event IDs, run IDs, invocation IDs and timestamps. Journal sequence remains authoritative within a stream; cross-source order is qualified by capture times. |
| 23 | Informational compaction messages add volume | `DiagnosticCoalescer` aggregates repeated informational replay-compaction records while retaining counts, first/last times and peak raw/replay/elapsed measurements. Overrun warnings and errors remain individual records. |
| 24 | Export completeness is unclear | The manifest adds a capture-completion percentage, its precise stage-based denominator and a missing-evidence list. Archive integrity and successful stage collection do not imply that deleted, evicted or uncaptured application history exists. `capturedFully` remains false. |

## MCP discovery and privacy

`ModernMcpTransport` implements a bounded compatibility decision for discovery. An HTTP 400 response that is not a recognized modern validation or quota error can trigger one legacy initialization attempt. An unsupported-version response must identify the requested version and valid supported alternatives. Malformed discovery, mismatched response IDs, invalid schemas, duplicate names and invalid pagination are rejected before publishing tool readiness.

Authentication and endpoint diagnostics identify the corrective action. Google API-key authentication is carried in the expected header and included in connection identity. Authentication failures, quota errors and invalid-parameter responses do not authorize replay of a tool call. No write retry was added.

`McpClientManager` latches definitive protocol/configuration failures for the connection identity, preventing repeated malformed discovery across automatic network-epoch retries. An explicit reconnect/refresh or a configuration change permits a new attempt. Transport failures keep the existing bounded health/recovery behavior.

`AgentToolResolver` checks local-only context restrictions before cold MCP discovery and metadata acquisition. `PERSONAL_DATA_LOCAL_ONLY` remains a policy restriction. The repair prevents unproductive network discovery and explains the requirement; it does not broaden authorization or bypass the privacy boundary.

The original two endpoint configurations and credentials were not available here. Deterministic transport tests establish handling of the reported failure classes, not present reachability of those deployments.

## Android rendering changes

Search receipt JSON and URL projection run on `Dispatchers.Default` instead of the composition thread. Visible progress-line projection is remembered for unchanged inputs. Summary translation reads animation state in `graphicsLayer`, so each animation frame does not require text composition and measurement.

Completed progress history stops its continuous animation. Active motion respects lifecycle visibility and the existing frame-pressure guard, retaining a visible stationary progress phase when optional effects are reduced. Only the latest row that uses the gradient blend requires forced offscreen composition; previous rows return to automatic composition after their fade.

These changes target concrete main-thread and rendering work visible in source. They do not identify the cause of every reported stall or establish a measured improvement on the 165 Hz device. The existing prompt-anchor, scrolling, progress and frame-pressure regressions remain relevant acceptance gates, followed by an affected-device trace.

## Export bounds and compatibility

The capture identity changes to `staged-v2`, preventing reuse of an old capture plan under the new section/chunk semantics. The staged archive schema remains compatible: chunk envelopes are complete JSONL records, and manifest metadata explains their reconstruction. Consumers that need logical messages must group chunks by `recordId`, require every index, validate every chunk hash, concatenate in order, validate the record hash, and only then parse the reconstructed JSON.

Normal source rows are bounded at 1 MiB; reduced-memory source rows are bounded at 256 KiB. Smaller independent JSONL chunks remain within record limits, including JSON escaping and multi-byte UTF-8. Sources are measured before hydration. Stage/archive limits, memory admission, privacy revocation, redaction, cancellation and resume-integrity checks remain enforced. An interrupted chunk sequence is incomplete evidence, even if the chunks already written validate individually.

Journal export retains only one decoded vault batch at a time, plus compact sequence intervals and boundary metadata. Old streams without durable checkpoints remain explicitly tail-unknown. Existing history lost before this patch is not reconstructed.

## Validation and qualification

The adjacent task JSON records validation commands, results and publication status. Focused host suites exercise production implementations with explicit Android or process boundaries where needed. They are not a substitute for the integrated Android build.

The editing host has Java 17 and no configured Android SDK. Its configured Java 21 distribution endpoints rejected download attempts. Full Android compilation, integrated unit/Robolectric tests, lint, packaging and emulator results therefore come from the branch's exact-commit GitHub Actions checks.

Remaining live acceptance includes the original MCP endpoints and credentials, an authorized end-to-end GitHub run against the running application build, provider-reported usage when available, process-death recovery on Android, and frame measurements on the affected physical device. No device performance percentage, live endpoint recovery, exhaustive mutation coverage or newly released application build is claimed by this source repair.

## November 6 follow-up: Android sharing and visual repository Status

The later diagnostic export/access report and UI request were added to this same PR without replacing the previous repairs. Android conversation text/diagnostic archives now use an external app-file destination when available and a FileProvider-covered diagnostics cache when external storage is unavailable. Before opening the sharesheet, the export is checked for nonzero length and opened through its content-provider URI on an IO dispatcher. The chooser and its nested ACTION_SEND both carry ClipData and read-grant flags. The old chooser package enumeration and unconditional per-package URI grants were removed. Robolectric tests exercise the actual chooser intent structure and reject raw file URIs. This addresses the Android export-sharing path; it does not establish that an external ChatGPT upload has been ingested.

GitHub Status now renders a themed network panel pinned to the selected repository's observed head and selected gateway AI profile. It reads the existing authorized private graph service, visualizes actual indexed architecture communities or symbols as bubbles, and exposes tap inspection, index and refresh actions. The view reports no-index, truncated, stale and gateway-unavailable states rather than inventing dependencies. A Compose native-render test checks the production network canvas and bubble selection. The Settings explorer remains available for detailed source queries.

A previous exact-head CI attempt at `c515c9f3e21b11d8d50f76c459c2aa06240764e9` failed two behavioral unit tests: independently authorized releases with identical HTTP dispatch bodies reused an earlier run receipt, and a test expected an identical payload to repeat after changing only a precondition inside the same task. The workflow dispatch now relies on the outer operation-scoped durable journal, retaining distinct authorized execution identities and no replay after an uncertain response. The test now explicitly distinguishes same-task duplicate prevention from a newly reviewed task scope. The one-off review workflow that violated concurrency policy was removed. Windows/Python 3.12 durable diagnostic stress had exceeded the unchanged 300-second budget; verification digest lookups are now batched per bounded ingestion request, with FULL synchronous WAL commits, 50,000-event stress and retention contracts intact.

These changes require newly executed exact-revision Android full-unit, lint, emulator, CodeQL, gateway/stress and canonical Required verification before ordinary merge. No stale success counts, device benchmarks, signed publication or private-host connectivity are claimed by source inspection. The release-scope allowlist includes the new regression files and its description records the added export and network features.

The final post-merge audit identified a changed-argument replay gap in that release-dispatch exception and a draft-release lookup failure before publication. The [November 7 reconciliation follow-up](diagnostic-final-reconciliation-2026-11-07.md) records those findings and their stricter acceptance contracts; the earlier identical-argument test alone did not establish the complete retry guarantee.
