# GitHub investigation recovery and resource pressure

Repository: tailscale-signin/GPT_Mobile_AI-improved
Base branch: main
Base commit: 061a3f0fa420e68148ee127c6fa58295cd943921
Working branch: fix/github-investigation-recovery-pressure-2026-11-02

## Objective and checkpoint

Implement the distinct failure families from the November 2 conversation export and the supplied 5,263-record diagnostic review. The latter review was supplied as text; its underlying JSONL was not attached to this task. The initial export does not include the final terminal record of its active run. A code excerpt from cancellation handling is not evidence of the cancellation trigger.

This document is the reviewable implementation checkpoint. Runtime recovery continues to use ToolBatchCheckpoint, its job context, and the encrypted GitHub evidence/engineering memory stores; it does not substitute this document for the runtime journal. Completed or uncertain mutations retain their receipts and are never replayed to recover output.

## Implementation and inherited safeguards

| Reported family | Resolution |
| --- | --- |
| Low diagnostic call ceiling and unproductive retry | Diagnostic acquisition allowance is up to 32 calls and 24 rounds, within the profile's limits. Plain interruption/failure questions and their recovery prompts are recognized. Explicit retries receive a new bounded tranche without charging historical dispatches again. Historical effect reconciliation remains intact. |
| Tool settings invalidate active requests | Normalize explicit default plugin grants before hashing. Actual grants, revocations, opt-in permissions and credentials still invalidate stale requests. Existing changed-field diagnostics are retained; recovery wording now describes the unfinished task rather than Combined synthesis. |
| Investigation grows without an answer | A bounded working ledger tracks new operation identities and recurring page failures. Four unproductive rounds or four page-failure rounds in an eight-round window move the runner to finalization. Existing no-tools finalization and retained failure publication remain bounded. |
| Unavailable saved pages | Existing active-reader TTL refresh and immutable source-recovery descriptors remain. Altered handles at an already-issued offset return the exact issued correction, without admitting invented offsets or revealing another scope's handles. |
| Same nonretryable request repeats | Cache saved-view preflight failures as well as invalid arguments, preserving the original correction and incident. Runner replay also preserves deterministic error receipts. |
| Wrong operation or recovery envelope supplied as arguments | Resolve only exact recipes already retained from tool results, only to a currently registered replayable GitHub read, after destination-schema validation. All ordinary authorization and budget checks still run. Never auto-unwrap a mutation. |
| Guessed paths | Existing pinned-tree membership validation and exact-path discovery remain. Correction guidance and final failure summaries explicitly direct discovery before another range read. |
| Excessive replay, compaction and allocations | Retained working exchanges are capped at 16,384 tokens; provider replay at 8,192; per-result replay at 2,048, additionally bounded by route capacity. Bulk authoritative evidence remains in existing durable stores. Replay retains exact recovery controls. |
| Tiny metadata fragments and graph pagination | Compact source reads omit verbose graph edges and suggestions while preserving source and its continuations. Full graph detail is explicitly available with response_detail=full. Local JSON fragments can bundle up to 1 KiB of related data instead of 160 bytes. |
| Search fan-out | Default new-blob acquisition is reduced from 16 to 4 in both schema and implementation. Explicit acquisition settings and exact continuation preserve exhaustive search when requested; unscanned files are not reported as searched. Existing commit-bound blob caches and path/symbol modes remain. |
| Heap pressure, allocation waits and UI stalls | Lower working-state bounds reduce repeated serialization/compaction volume. Existing bounded parse/highlight caches, 500 ms render preparation and history paging are retained. Glass effects temporarily yield under sampled memory pressure and recover afterwards. This is mitigation, not a measured leak/GC/frame-time diagnosis. |
| Repeated text measurement and incomplete timing attribution | Existing content/width/style-owned Compose text and parsed-block caches remain. Layout traces now include the rendered revision digest. No Placeable is cached across layout passes. Physical profiling is still required to identify remaining layout work. |
| Persistence latency and terminal durability | Existing conflated writer retains only the newest pending intermediate snapshot. Terminal drain now interrupts the intermediate coalescing delay and waits for the latest write and any storage failure. |
| Speech error 10 / busy recognizer | Serialize active recognition, debounce starts, impose a ten-second cooldown after too-many-requests and a shorter failure cooldown. Release recognizers on final/error/construction failure before invoking callbacks. Late callbacks remain fenced. |
| Inactive keyboard connection warnings | Hide the IME and clear focus when the chat screen disposes. Existing input focus callbacks remain. Platform warnings alone are not classified as lost text. |
| Jina DNS failure / degraded MCP discovery | Distinguish DNS_RECOVERY from UNKNOWN, retain host-scoped health/backoff, and include a bounded retry delay for shared probes. A successful unrelated host does not clear a failed host. Network-change reset remains available. The existing 120-second abandoned-lease safety bound is preserved to avoid overlapping live probes. |
| Privacy-policy endpoint denial | Existing OpenRouter ZDR metadata preflight and unchanged serialized privacy policy remain; model aliases with a leading tilde now use the normalized model for eligibility. Classify the denial as nonretryable with compatible-endpoint guidance. Do not relax ZDR or repeat unchanged requests. |
| Ineffective Android foreground job flag | No explicit importantWhileForeground flag exists in the current source. The supplied review reports empty queues, not lost jobs. Do not modify WorkManager scheduling or remove useful work based solely on that platform warning. |
| Usage incomplete / tool-only outputs | Preserve actual terminal finish reasons on streamed provider events. Existing ledger keeps unknown totals distinct from measured subtotals; no token/cost estimate is promoted to confirmed usage. Tool-call-producing completions remain valid intermediate outcomes. |
| Empty causal incident IDs | Every operation wrapper receipt now has a stable incident ID, including local preflight failures, while retaining structured code, dispatch state and exact corrective controls. Existing payload references remain the authoritative full error representation. |
| Missing cancellation origin | Emit run ID, interruption flag, cancellation origin and worker presence at the cancellation request. This identifies future trigger paths without declaring terminalizeAndCancel itself the root cause. |
| Final report loses material failures | Append a deterministic trusted-receipt failure summary to diagnostic publications, independent of claim extraction. Include recommended recovery actions and explicitly separate task-time observations from an earlier interruption's cause. |
| Raw path/status presentation | Remove redundant source-path-only investigation tables. Existing status collection already includes commit subjects and separates skipped checks from actual test execution; retain those safeguards. Preserve current file excerpts without elevating them to a runtime diagnosis. |
| Incomplete stress test and unsupported score | Existing task coverage reports observed operations separately from executed tests and leaves missing cases unresolved. A larger bounded allowance supports additional checks; no fabricated all-tools score or test execution claim is added. |
| Repetitive progress | Preserve the current concrete activity when a model round starts, rather than replacing it with a generic reviewing notice. Existing path-focused, twenty-second summaries remain. |
| Partial export / truncated records | Existing capture-completeness, loss counters, run status, payload references and usage provenance remain. This patch adds incident and cancellation linkage; it cannot recreate records absent from the original capture. |

## Validation checkpoint

- `bash scripts/validate-local.sh fast`: passed, including 287 Python host checks, Android resource/regex checks, icon and QNN provenance checks.
- Host Kotlin component tests: 19 passed. A broader harness then compiled the actual runner, checkpoint, GitHub operation, pagination and verification sources and passed 495 host JVM regressions. Android infrastructure and unavailable transport adapters use explicit host fixtures; three Android adapter/classifier integration cases are excluded rather than represented as passed. These results do not establish an Android app build or physical-device qualification.
- Additional Android/JVM regressions cover explicit retry with historical receipts, exact read recipe routing, mutation exclusion, scoped cursor correction, explicit-default policy stability and terminal checkpoint drain.
- Targeted Gradle invocation stopped before compilation: the Gradle 9.8.0 distribution download was unreachable on this host. JDK 21 / Android SDK 37 app compilation and Android-bound tests require CI.
- Physical heap, GC, speech service, frame-time, live MCP/Jina DNS, endpoint eligibility, final-run termination and full GitHub stress-test measurements remain unverified. No OOM, fatal exception or ANR is inferred from the supplied review.

- Release-scope validation is already inconsistent at the selected main baseline: its October 31 base omits later main changes from allowedPaths. This patch does not rewrite historical release qualification or bypass that gate.

- CI identified a stale architecture index after adding the new sources; the branch now includes the generated refresh. Broader regressions also verified invalid cursor byte ceilings, the four-blob default with full continuation/cache coverage, and the ordinary schema ceiling (997 estimated tokens in that test).

## CI repair after c3cdf4e4d4a52954d17c6c8c6a2b23f856f1010e

The complete Android unit-test job reported 6,004 tests, 84 failures and four skips. The retained failures identify two regressions introduced by this branch: serialization inferred `SortedMap` for normalized plugin settings, and OpenAI/Groq adapters emitted finish-reason usage already owned by their assembler. The preparation failure propagated through ordinary chat, Combined runs and provider-wait recovery; the earlier host runner harness does not cover those Android repository integration paths.

The policy projection now builds an ordered JSON object of effective nondefault grants directly, without polymorphic JVM map serialization. It preserves opt-in grants and explicit revocations while making explicit defaults and insertion order immaterial. Focused host regressions cover empty and sorted-map inputs, default equivalence, serializable output, grants and revocations; both pass. An integration regression exercises `ConfiguredDispatchAuthority.policyFields` with actual feature settings. Existing exact event-sequence tests in `ProviderAdaptersTest` retain one terminal usage event and exact tool-call identity; duplicate adapter emissions were removed rather than weakening those assertions.

Repair validation: the two focused JVM policy tests and all 287 fast host checks pass. Changed-file formatting, architecture index and whitespace checks pass. The full Android CI suite is rerun on the repair commit; its eventual result must be read from that commit's checks rather than inferred from these host results.
