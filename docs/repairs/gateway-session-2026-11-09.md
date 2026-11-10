# Gateway session causes, correlation and support evidence

This follow-up is on PR #816, `fix/audit-execution-recovery-2026-11-09`. It started at `e85dec10ec07b1a7dd58e4a4237a3c9e81572d6d` and incorporates the intervening map/defaults/verification update `b60e619ab054464a85e87063939f85fa6de76b83`. It also retains the subsequent graph-identity and audit-evidence fixes through `ba0ff0a0c95bf1bf703236937f7a9939e93d45ba`. Base remains `main` at `7db0c735f9e86eaece24513fbaf5c0a624f13822`.

The supplied 4,197-record review is historical evidence, not a fresh device test. Its 72 HTTP 503 observations cannot establish a single failing dependency. This change preserves the reasons needed to identify that dependency and exposes repair guidance; it does not claim that the user's PC backend has recovered.

## Design and wiring

The reviewed JSON contract in `docs/contracts/gateway-diagnostic-schema-v2.json` generates both Kotlin and Python allowlists. The fast validation gate rejects a stale generated implementation. Schema v2 remains wire-compatible; the contract digest identifies the exact additive vocabulary. Raw prompts, tokens, tool arguments, endpoint URLs and exception prose remain excluded.

Gateway metadata requests create a local physical-attempt record even when the optional client-metadata upload header is absent. Matching uses the outgoing trace context if a response does not echo it. Debug/private-session policy is checked at dispatch, ingestion and export. A response without an observed attempt explicitly says `response_only`. An HTTP 200 observation is never selected-model or task-completion proof.

Readiness keeps top-level storage/process blockers ahead of backend causes. Safe model/profile digests, dependency, reason, upstream status, observation age and fixed recovery actions survive to status and export. The profile status keeps the existing themed online indicator and adds a readable repair hint; diagnostic details expose the structured reason and measurement. Existing coalesced observation, backoff and model-specific freshness rules remain authoritative.

The support ZIP includes the unchanged phone mirror and, when accessible, the gateway's committed session ZIP. Each source retains its own checksum, sequence and coverage manifest. The device manifest adds a bounded database snapshot of current saved runs; the gateway manifest adds owner/session-scoped engineering jobs, checkpoints, terminal receipt summaries and evidence endpoint references. A corrupt, wrong-session, oversized or unavailable gateway archive never makes the phone capture unusable. Export has a bounded timeout, bounded streaming and archive validation, privacy-epoch checks, and temporary-file cleanup.

The combined manifest intentionally says `complete=false`: full protected app records, generation-job receipts and full engineering evidence bodies have independent retention/access. Source-event digests and evidence references are links, not a claim that their payloads were downloaded. No export grants authority to execute or replay a job.

## Findings addressed

| Finding | Implementation and evidence boundary |
| --- | --- |
| 1. Repeated 503 / readiness | Preserve typed dependency/cause, selected identity, upstream HTTP evidence and original observation age. Recovery hints distinguish model load, backend, access, configuration, storage and draining. Actual deployed dependency recovery still needs a new capture. |
| 2. Unexplained prechecks | Projection and saved-run snapshots retain reason, stage, blocked-action scope and recovery condition. Existing argument rejection before budget acquisition, authority refresh and bounded failure pauses are retained; no automatic replay of rejected writes is introduced. |
| 3. Ambiguous terminals | Preserve terminal status independently of severity, disposition and criterion counts. Engineering final reports now persist acceptance-aware receipt summaries. Capture-time database inventory distinguishes active work from a missing terminal log. Missing acceptance remains explicit. |
| 4. Discarded extracted causes | Reuse normalized diagnostic execution evidence and retain allowlisted reason/stage/disposition/recovery fields. |
| 5. Schema disagreement | One JSON source generates both allowlists. Cross-runtime fixture exercises Kotlin spool → Python ingestion/restart/export → Kotlin import/export for every approved attribute value. Vendor-log availability is deliberately always false. |
| 6. Response-only HTTP failures | Instrument gateway exchanges without requiring metadata-upload opt-in, keep outgoing physical trace, local timing and response-only status. Eligible inference/probe/file operations retain pre-header failure and cleanup evidence too. |
| 7. Route mismatch | Shared full route vocabulary; explicit capabilities, readiness, commands, job catalog and engineering labels. Unknown endpoints retain `other`. |
| 8. Generic network failures | Originating gateway transport captures DNS/refusal/connect/read/request deadline/TLS/reset/EOF categories, endpoint digest, physical attempt and retry/reconciliation policy. Unrelated legacy global network events do not acquire a fabricated target. |
| 9. MCP warnings | Typed failure producer exports phase, pseudonymous server, operation identity, cause, status and recovery action. Discovery remains distinct from execution. Background discovery has no invented run ID. |
| 10. Renderer losses | Read actual `frames`, `jankFrames`, `droppedSamples`, duration/percentile/deadline/refresh metrics. Export nanosecond units and thresholds (100 ms severe frame; 5% missed deadlines). Separate long-frame, jank and measurement-loss flags; lost samples never become dropped display frames. |
| 11. Causal context | Normalized run/invocation and explicit operation/job/tool/binding aliases survive projection; bounded non-opaque labels are digested. Global/background records without run context remain unassigned. Trace relationships, not equal-looking request IDs, connect physical exchanges. |
| 12. Cancellation | Timeout cancellation is a deadline; typed observer detachment and supersession retain their origin and normal coroutine propagation. Observer teardown does not cancel a durable gateway job. An untyped cancellation honestly remains unknown. |
| 13. Poll attribution | Shared availability owner emits active/background observer counts, trigger, dependency transition and next eligible delay. Existing tests cover coalescing/backoff; a five-second sample alone does not prove excessive polling. |
| 14. Runtime provenance | Schema/gateway/configuration digests join app build and revision; linked snapshots include safe model/profile identity and durable job checkpoints where recorded. Existing captures cannot be retroactively enriched. |
| 15. Clock uncertainty | Local monotonic durations and source sequences remain authoritative. Support manifest explicitly forbids cross-host wall-time subtraction for latency. No unmeasured clock-offset estimate is invented. |

## Validation

| Check | Result |
| --- | --- |
| Canonical fast gate | 444 tests passed |
| Gateway full software suite | 727 Python cases: 726 passed, 1 environment skip; 7 JavaScript passed |
| Diagnostic projection/privacy | 147 passed |
| Availability, client correlation and readiness | 62 passed |
| Support ZIP, persisted inventory, transport and spool | 34 passed |
| Gateway capability and inference/probe clients | 51 passed |
| MCP connectivity/discovery/execution | 61 passed |
| Cross-language full persistence path | 962 approved values passed |
| Changed Kotlin formatting | 36 files passed ktlint 1.3.1 |

The final engineering last-completed-stage addition was followed by the targeted engineering and contract suites; their counts are in the receipt. Results and source hashes are retained in `docs/repairs/evidence/gateway-session-2026-11-09/validation.json`. Host Kotlin suites compile the relevant production sources with explicit Android/service boundaries; they do not constitute Android/Room/Hilt/Compose execution. Counts from overlapping suites are not a coverage percentage.

Full Android validation was attempted with `bash scripts/validate.sh all`. Resources, icons and QNN provenance preflights passed, but Gradle 9.8.0 download failed with `Network is unreachable`. The published combined head still requires its own CI build, unit/lint, UI review and emulator results. Physical-device performance and selected-model recovery remain unmeasured.

To rerun the cross-runtime check, compile `scripts/diagnostic-roundtrip/GatewayDiagnosticRoundTrip.kt` alongside the production diagnostic event/schema/spool/sync/retry sources and their JVM dependencies, then run `python3 scripts/check_diagnostic_roundtrip.py --kotlin-classpath <compiled-output-and-runtime-classpath>`. It uses temporary directories and the real gateway journal, reopens committed storage, checks manifest schema identity, and verifies both local and imported fields in the final phone ZIP.
