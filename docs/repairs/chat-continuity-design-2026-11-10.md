# Quieter chat and reliable retained-response delivery

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. PR: #816. Working branch: `fix/audit-execution-recovery-2026-11-09`. This change starts at `71d8001fbe62451cf0a1af4a4f77fda06153311f` and preserves the previous Gateway 16, local voice and GitHub acceptance work. Before publication it was reconciled with concurrent head `233f0538e79f3a283fad5c40d085868e60044bff`, retaining its overlapping cleanup and release-scope changes.

## Delivered changes

- Remove the routine execution-location sentence from the chat progress header. Detailed execution ownership remains available in the Gateway panel.
- Run memory sampling and cache maintenance silently. The maintenance effect emits no layout nodes or accessibility announcement; health samples and diagnostic records remain available.
- Keep ordinary activity text on one line. Its elapsed timer updates independently once per second, using tabular numerals. The GitHub progress indicator remains intact.
- Observe viewport and padding changes in ordinary chats as well as GitHub chats, so keyboard/composer changes can preserve the bottom anchor. User history-reading and finger-drag guards remain in force.
- Preserve zero remaining task time instead of inventing a five-second runner attempt. Check the accepted remaining time before checkpoint work and again before the execution loop. An expired task delivers retained evidence without restoring queued calls, invoking a model, renewing its budget, or closing the original execution checkpoint.
- Keep explicit saved-report and interruption-explanation requests available after expiry. Cancellation remains cooperative during report delivery.
- Preserve the original deadline or provider failure in both the emitted event and persisted task state. Unresolved approvals remain secondary evidence. An uncertain remote write still takes precedence and requires reconciliation.
- Show concise provider rate-limit guidance and preserve HTTP/cooldown metadata. A failure does not replace or append an error explanation to an already visible partial answer.
- Replace unsupported generic attribution to a model stopping with a neutral explanation of unverified requirements. Shorten approval guidance and avoid claiming a change is unpublished merely because its saved approval remains unresolved during an interruption.

## Evidence scope

The supplied capture is historical evidence from app `0.9.66.7`, build `141`, app source `7eceaa54dde4d77100898a7ac09e5d2688a65f96`, inspecting repository revision `7db0c735f9e86eaece24513fbaf5c0a624f13822`. Capture ran on November 10, 2026, 08:11:23–08:12:29 America/Toronto; retained attempts span 01:06:38–08:10:57.

All 20 section file hashes and byte counts validated. The 3,803 physical JSONL records include 1,681 chunks reconstructing 65 large records, yielding 2,187 logical rows. No malformed JSON, missing chunks, hash failures or missing earlier archive part was found.

Ten attempts ended with five approval blocks, four partial outcomes at 3/5 criteria, and one HTTP 429 failure. None completed. These are ten attempts across eight persisted assistant messages, not ten independent tasks.

The capture has nine application-generated reports and one recorded model invocation, which failed before producing text. It does not establish repeated interrupted token streams. The two requested UI labels do not occur in the transcript; their presence was confirmed in current source.

## Issue inventory

“Earlier repair” means implementation exists in this PR before this change, inspected alongside the historical evidence. It is not a claim that the supplied APK executed that implementation successfully.

| ID | Finding and evidence | Resolution or remaining work |
| --- | --- | --- |
| 01 | Routine execution-location text occupies the chat progress header. Source: `ChatGenerationProgress`. | Removed in this change; detailed ownership remains in the Gateway panel. |
| 02 | Successful cache cleanup creates a pressure banner above the composer and an accessibility announcement. Source: `ChatMemoryStatus`. | Made silent while retaining maintenance and diagnostics. No causal claim that the banner stopped inference. |
| 03 | Ordinary progress text animates dots every 500 ms and can wrap to two lines. | Stable single-line text and independently updating one-second timer. |
| 04 | Ordinary chat omitted viewport/padding-only changes from its scroll observation key. | Fixed; both chat modes retain bottom follow while respecting detached reading. Source finding, not a measured device incident in this archive. |
| 05 | Latest answer reports an artificial 5,000 ms timeout after the accepted task deadline had expired. `conversation.jsonl`, message 76; `runs.jsonl`, row 11. | Separate retained delivery from work admission; no new queued-call execution after expiry. |
| 06 | An old approval replaces the timeout as the terminal explanation and persisted state. Same latest run. | Preserve original cause in final event, structured stop reason and persisted state; retain secondary approval obligations. |
| 07 | HTTP 429 becomes generic `GITHUB_RUN_INTERRUPTED` / `BLOCKED` prose although the invocation is `FAILED`. Message 70; invocation row 1. | Preserve typed cause and cooldown; concise request-limit guidance. External provider availability is unchanged. |
| 08 | Four deterministic incomplete runs blame the model without corresponding successful inference evidence. Runs rows 4–7. | Neutral fallback wording; concrete scheduler causes from earlier repair remain preferred. |
| 09 | Five verification answers contain roughly 1,550–1,569 words and eight tables each. Messages 62, 64, 72, 74 and 76. | Design follow-up: short outcome and one action, with the full criteria/tool ledger in expandable details. This change does not remove the full evidence report. |
| 10 | Stored progress has 225 notices, 53 summary snapshots and 108 “available for review” messages. | Earlier bulk-restoration fix removes false fresh-result callbacks; current stable row reduces layout churn. Further semantic summary consolidation remains a design follow-up. Stored counts do not prove simultaneous display. |
| 11 | Approval-confirmation messages can be followed by unresolved-approval reports. | Earlier exact task/repository/branch/parent/result-SHA reconciliation is retained. A genuinely new cleanup approval must remain distinct; not every repeated card is a duplicate. |
| 12 | Six complete tool inventories each contain 15 entries, but unrelated `web_search` schema drift invalidates GitHub catalog acceptance. Tool rows 1, 2, 43, 70, 71 and 113. | Earlier repair isolates GitHub contracts and preserves received versus verified inventory. More pagination would not fix this fingerprint mismatch. |
| 13 | Planner prerequisites remain blocked after PR/workflow/release identifiers have been observed. Tool rows 9, 32–35, 78 and 102–105. | Earlier dependency-aware recipes expose exact next reads and ready-but-untested actions. |
| 14 | Historical all-tools scope includes 104 capabilities beyond the content fixture's supported lifecycles. First checkpoint: 41 passed, 62 blocked, 1 out of scope. | Earlier new-preset fixture matrix narrows future supported tests explicitly. Historical acceptance cannot be relabeled complete. |
| 15 | Resumes repeat unchanged work: one 89-second attempt restores 69 results with no new tool admission; two later attempts each take about 24 seconds. | Earlier bulk hydration, semantic advancement and explicit stop reasons are retained. This change repairs expired-result delivery and primary failure reporting. |
| 16 | First disposable fixture cleanup is verified; the second is recorded `CLEANUP_PENDING`. Tool row 57 versus the latest checklist. | Outstanding recorded obligation. Reconcile current exact state before cleanup; this audit does not delete fixtures or establish their present existence. |
| 17 | All 118 tool envelopes are completed/non-error, but constituent receipts record 204 HTTP reads with four failures. | Earlier subordinate-failure diagnostics retained. Successful envelopes are not proof all requests succeeded; unavailable protection data cannot mean no branch rules exist. |
| 18 | App logs are absent while a separate diagnostics setting is enabled. Both app-log sections: tracking disabled, zero retained records. | Earlier collection-state clarification retained. A new capture with appropriate logging is required to qualify crashes, rendering and disconnects. |
| 19 | Manifest says 90% and resumable, but there is no unfinished continuation; missing logs were never collected. | Earlier capture recovery classification distinguishes missing source from unfinished export. Resume cannot reconstruct unrecorded history. |
| 20 | Capture takes 66.119 s; workspace is 23,614,161 of 28,560,826 section bytes. Process counters increase by about 4.61 GB allocated and 75 GCs. Final Java heap is about 82.2% of 256 MiB. | Earlier checkpoint deduplication, bulk restoration and journal batching retained; measure on the device. Process-wide counters do not prove exporter-only attribution, an OOM or a UI freeze. |
| 21 | Prepared request/context records and old empty normalized policy tables do not establish every actual dispatch. Six workspace policy snapshots are present. | Earlier minimal invocation/admission evidence retained. Do not infer missing model calls solely from deterministic application reports. |
| 22 | Invocation input is estimated at 20,749 tokens; serialized admission estimates 21,334. No confirmed provider token usage or cost. | Keep reservation/serialization estimates separate from measured usage. This is not evidence of context overflow or its causing HTTP 429. |
| 23 | Redaction removes benign authorization-state enums. | Earlier typed-state redaction repair retained; secrets remain redacted. |
| 24 | Requirement memory includes headings, budget lines and preset controls as deliverables. | Earlier canonical requirement parser retained. |

All 109 acceptance entries are retained across both diagnostic pages; the 64-entry compact projection does not mean 45 criteria were lost. All five observed `verify_live` requests performed live reads with zero cached-read observations. Separate app and repository SHAs are valid provenance, not inherently a mismatch.

## Validation

`bash scripts/validate-local.sh fast` passed all 448 Python tests, policy/schema/inventory checks, Android literal-regex checks and resource preflight after the generated app-code inventory was refreshed. `git diff --check` passed.

Behavior regressions were added or extended for expired-task delivery beyond five seconds, queued-write exclusion, cancellation, report-only and diagnostic delivery after expiry, persisted failure/approval precedence, provider rate limits and uncertain-write precedence, and viewport/composer changes in both chat modes.

Kotlin/Robolectric and full Android tests did not execute locally. The required Gradle 9.8.0 distribution could not be downloaded (`Network is unreachable`), and no Android SDK or usable Kotlin/Gradle cache is installed. The 448 host checks do not compile these Kotlin changes. Exact-head Android CI and a fresh device run remain required for runtime qualification.

The implementation follows Kotlin's cooperative cancellation contract: retained delivery stays cancellable rather than using `NonCancellable`. Routine cache cleanup remains in place, consistent with Android memory-management guidance.

References: [Kotlin timeout semantics](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout-or-null.html), [Android memory management](https://developer.android.com/topic/performance/memory/manage-app-memory).
