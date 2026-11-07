# Diagnostic follow-through — 7 November 2026

## Scope and delivery

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Requested branch: `fix/diagnostic-remediation-8597579-2026-11-07`.
Starting source: `c980ad3b4c13f8d9dd8bb4b5a393f4263d3b197f`.
Parent PR: #811, subsequently confirmed merged as `737303c4f82eead75b5f554fc29201a9f9a43b82`.

This follow-through implements the 27 numbered findings from the later diagnostic archive audit. It extends the existing remediation branch and preserves its authorization, journal integrity, cancellation, selected-route, and no-mutation-replay safeguards. The private archive and its decoded contents are not committed.

The audit distinguishes a response ending from a task passing acceptance. It also distinguishes configuration from observed execution, repeated log records from independent incidents, and recoverable presentation problems from a need to repeat remote work.

## Coverage ledger

| # | Finding | Implementation or retained safeguard | Verification boundary |
|---|---|---|---|
| 1 | Qwen rejects late or repeated system frames | Normalize a single leading instruction prefix at final gateway dispatch; compaction, synthesis and repair preserve that contract. | Strict-template behavioral regressions, including repaired and compacted requests. |
| 2 | Final-answer mode rewrites completed tool results as user text while continuing the same job | Preserve exact assistant calls and tool-result IDs; durable pending results are consumed by the original job. Restore only a verified waiting handoff under current authority and the same profile, endpoint and model; completed historical batches may reuse opaque IDs. | Serialized request and gateway handoff tests; completed tools are not re-executed. |
| 3 | Deterministic template failures are retried | Recognize structured and legacy template failures as nonretryable protocol errors at HTTP and SSE boundaries. | Attempt-count regressions. |
| 4 | Original failure cause and retryability are lost | Preserve structured original cause, stage, dispatch state and retryability through saved-result recovery and terminal receipts. | Recovery and classification regressions. |
| 5 | Retry loses the remote job identity | Publish job headers even on failed HTTP responses and persist the identity before saved-result lookup. Unknown retained job IDs cannot silently create fresh jobs. Implicit lookup uses only the current result-return suffix; historical results before a new user turn cannot revive a terminal job. | Recovery metadata and unknown-job tests. |
| 6 | Failed retries leave an empty answer despite retained evidence | Keep partial answer text separate from authoritative error notices; render terminal errors even before the timeline notice is restored. | Partial-result and presentation tests. |
| 7 | GitHub preflight spends its budget in reasoning or repair | Apply a concise helper budget, prefer verified reasoning OFF, and account for actual generated usage in repair allowance. | Helper budget and gateway repair regressions; live model latency remains a deployment measurement. |
| 8 | Planner guesses a repository although executor has a default | Freeze the selected repository using the same store/default rules as execution; reject fabricated targets before dispatch; allow explicit user targets and authorized repository discovery. | Target-policy and resolver tests. |
| 9 | GitHub 404 is explained as proof of insufficient OAuth scopes | Explain hidden/missing/wrong-target possibilities; distinguish accepted scopes from granted scopes. | Structured failure and guidance tests. |
| 10 | Response completion is recorded as verified task completion | Run substantive native GitHub repository work in standard chat through the existing evidence and acceptance session; preserve expectations across recovery. Exclude unrelated chat and account-only discovery; missing or ambiguous repository targets stop before dependent dispatch. | Acceptance-policy and terminal-state tests. |
| 11 | Pagination repeatedly requests page 1 and counts changing pages as progress | Preserve exact continuations, stable request identity and captured workflow pages; detect repeated/nonprogressing pages and count unique records. | Pagination, overlap and continuation tests. |
| 12 | Inventory counts and tested/remaining counts disagree | Compute inventory, advertised, executed and acceptance counts from distinct evidence; reject unsupported numeric progress claims. | Coverage and progress-summary tests. |
| 13 | GitHub discovery includes unrelated tools | Limit task-relevant discovery without changing underlying grants; retain required GitHub schemas through admission. | Discovery relevance and context-admission tests. |
| 14 | A brief test/greeting resumes stale GitHub work | Classify the current user request before adding workspace context; greetings and connection checks do not inherit a substantive old task. | Current-intent regressions. |
| 15 | Direct inference route repeatedly receives unsupported capability probes | Cache only definite 404/501 capability results for five minutes, bounded to 128 entries and the exact route, credentials, network and runtime revision. | Mock HTTP and expiry/identity tests. |
| 16 | Engineering graph probe runs on a confirmed unsupported route | Reuse confirmed route availability, retain separate engineering readiness, and preserve native/local fallback behavior. | Engineering-client and graph-fallback tests. |
| 17 | Protected gateway metadata produces repeated 403 probes | Prefer verified gateway selected-model metadata; remember only the explicit remote-surface admission denial; mark reused observations as cached. Relay reasoning controls only with verified template-probe evidence. | Readiness, capacity and reasoning-proof tests; gateway admission remains enforced. |
| 18 | A stale read-only connection produces EOF | Retain the existing single fresh-connection retry and idle-socket eviction. Authentication, deterministic errors, cancellation and mutations remain excluded. | Existing read-recovery tests rerun. |
| 19 | Invalid MCP protocol/schema configurations recur | Retain configuration-fingerprint failure caching, nonretryable codes and explicit reconnect guidance; unrelated connectors stay usable. | MCP transport, catalogue, recovery and presentation tests rerun. Server-side schema repair still requires the affected deployment. |
| 20 | One optional connector cannot resolve DNS | Surface DNS as a distinct retryable connection failure with address/DNS/VPN guidance; preserve the saved catalogue and independent connections. | DNS presentation and isolated MCP recovery tests. The application cannot repair an external DNS zone or VPN. |
| 21 | Generic reasoning OFF disagrees with effective AUTO | Migrate missing Llama mode preferences once; preserve explicit enum choices, apply conversation overrides, honor hard OFF constraints and show the same resolved preference in UI and exports. | Preference and request-policy regressions plus migration wiring review; unverified controls stop before inference. |
| 22 | First-output timing conflates backend and visible response | Retain separate measured backend reasoning/visible/first-output timing and Android first-visible rendering evidence. Buffered transport cannot invent an earlier client token timestamp. | Timing-envelope and invocation evidence tests. |
| 23 | Rich-text measurement stalls the main thread | Page responses above 8,192 characters instead of using the 32,768-character storage threshold. Retain off-thread preparation, bounded page caches and draw-only streaming fades. | Paging/presentation policy tests; physical frame-time and allocation qualification remains required. |
| 24 | Routine tool omissions overwhelm diagnostic logs | Emit one bounded count/reason/sample/digest summary per changed stage and sampled repeat summaries; keep required-tool failures as warnings. | Aggregate-count, ordering and bound tests. |
| 25 | Follow-up chips ask the assistant to supply missing user information | Reject clarification questions both during extraction and at action dispatch, while preserving factual follow-ups and executable offers. | Suggestion-loop regressions, including normalized offers and cached batched prompts. |
| 26 | Old failure text is concatenated with resumed answer text | Preserve replacement/continuation semantics and hide stale failure banners for completed runs; errors remain outside answer evidence. | Recovery and presentation regressions. |
| 27 | Inactive input connection warnings recur | Make the composer the sole owner of keyboard release, release once on disable/stop/disposal, and remove unconditional parent teardown that can target another editor. | Source/lifecycle review; physical IME transition qualification remains required. |

## Additional observations

- Memory work already persists source references before scheduling, distinguishes semantic waiting from successful enrichment, and wakes on compatible model/idle/admission changes. Preserve those paths; a WorkManager `SUCCEEDED` state alone is not semantic completion. The diagnostics screen already counts enriched and waiting work separately.
- The archive does not establish a leak, OOM, ANR, silent model fallback, or a blur-caused stall. Do not fabricate fixes or performance claims for these. Keep bounded caches and measured traces.
- Policy-stale rejection remains a correct pre-dispatch stop. Completed receipts and unknown mutation outcomes remain retained; recovery does not create permission to replay a write.
- Runtime/model provenance must come from the actual response and selected execution identity. Accelerator use is unmeasured unless the serving runtime reports it; physical QNN/GPU qualification requires compatible hardware.
- Diagnostic exports now include explicit gateway selections and legacy/explicit/resolved reasoning preferences, labeled `CURRENT_SNAPSHOT_NOT_EXECUTION_PROOF`. These are current settings, not historical routing evidence. Existing chunk reconstruction, hashes, sequence coverage, private-capture handling and redaction remain required.

## Integration regressions found during review

- Native GitHub acceptance is scoped to repository work. Unrelated math, Drive and account-discovery requests do not inherit it. Missing or ambiguous targets stop before a saved-job read, provider session or dependent tool dispatch. Repository and connection guards run before preparation and execution on normal, dynamic and LiteRtLm native tools; explicit different targets cannot leave the accepted scope.
- Implicit gateway handoffs use the active trailing result-return suffix after instruction-role normalization. After a verified terminal-job read, Android relocates the actual latest user continuation after retained native history until a new job identity arrives; it never fabricates or duplicates that user frame. Missing current-user or original-task anchors stop before HTTP. A new user turn can retain historical native tool exchanges without reviving their terminal job; genuine duplicate delivery still reads or rejects the old terminal job.
- The MCP active-cancellation test now cancels after an actual dispatch rather than after a timing guess. It still requires one dispatch, prompt cancellation and process cleanup. A separate regression verifies that cancellation before dispatch leaves an unrelated warmed session alive.

## Deployment qualification still required

These checks require the affected installation and are not marked complete by host tests:

1. Install the matching Android and gateway builds. Retain the existing gateway journal. Run a read-only GitHub task with the actual selected repository, then disconnect and restore the application while a client-owned tool result is pending. Confirm the same job and call identities resume, including a failed tool result, with no replay of a completed action.
2. Exercise a normal, compacted and repaired Qwen conversation. Confirm there is one leading instruction message at each physical dispatch and no deterministic template retry. Inspect the original structured failure when deliberately submitting an invalid fixture.
3. Set reasoning OFF explicitly, then AUTO, and verify the preference, transmitted control, template-proof revision, backend timing and Android first-visible timing separately. A missing proof must stay unsupported or unmeasured; a fast response alone does not prove accelerator or reasoning state.
4. Match each opaque failed MCP connection ID to its entry in connection settings. Correct its endpoint, transport and protocol/schema response, then reconnect explicitly. Verify the permanent failure gate clears after a configuration change. Diagnose the separate Jina DNS issue from the affected device/VPN; do not identify the two schema failures as Jina without evidence.
5. Profile a release or profiling build on the affected phone while generating, scrolling long evidence tables, switching tabs and opening/closing the keyboard. Compare text-measure slices, frame-time percentiles, allocation rate and real GC pauses under the same workload. Check reduced motion and blur enabled/disabled separately. No physical performance gain is claimed here.
6. With semantic enrichment waiting for a model, restart the app, load a compatible local model and release competing inference. Confirm exactly one eligible enrichment resumes and that changed/deleted sources do not enrich. Distinguish semantic readiness from the previous worker scheduling result.

## Validation

The gateway aggregate, focused production-source checks and canonical local checks below passed. Final implementation source is `c682d6df3ec9e6dd93ed4d579033713990376b09`; the following documentation/metadata commit leaves that code unchanged. Exact-head Android CI remains required, and existing PR #811 passes do not qualify these changes.

| Check | Observed result |
|---|---|
| Full gateway aggregate | 48 Python suites; 556 passed, one LAN-interface test skipped, zero failures/errors. Seven browser bridge tests passed. All 166 gateway source hashes remained unchanged through validation at `68336351190e8b96058932ccbf52f1aa6acb0d7b`. |
| Durable gateway stress | 50,000 events and 782 commits passed. This measures host journal behavior, not phone frame pacing. |
| Authentication brokers | 25 tests passed. |
| Integrated gateway/capacity/recovery JVM host | 84 tests passed with JVM assertions and coroutine stacktrace recovery enabled on the integrated Android production sources. |
| Runtime network/reasoning/boundary JVM host | 34 tests passed in each coroutine stacktrace-recovery mode. |
| Failure classification and task scope JVM host | 22 tests passed. |
| MCP transport/catalogue/recovery JVM host | 78 tests passed. |
| GitHub catalogue/planning/pagination/narrator JVM host | 47 tests passed, plus six bound-target wrapper behavior tests. Initial, dynamic and LiteRtLm native dispatch hooks independently reviewed. |
| UI policy JVM host | 19 tests passed. The pre-change parser fails the repository-clarification regression; the changed parser passes. |
| Canonical fast | 378 script tests passed, plus CI policy, dependency catalogue, secret scan, Android regex, resources and whitespace. The regex checker handled 1,237 literal patterns and explicitly skipped 75 dynamic patterns. |
| Formatting, release scope and generated metadata | All 87 changed Kotlin files passed ktlint 1.3.1. Exact changed paths fit the preserved release scope. The architecture index and gateway manifest were regenerated. The Windows timing-fixture follow-up below changes one test entry; every production gateway entry remains identical to the passing aggregate. |
| Complete Android build, native adapters/coordinator, Robolectric, heap gates and lint | Exact-head CI required. Local environment has Java 17 and no Android SDK; the project requires JDK 21 and Android SDK 37. |

### Windows timing-fixture follow-up

PR #813's Windows Python 3.12 gateway job (`128459511122`) exposed a test-clock assumption: a real 5 ms sleep produced equal zero-millisecond readings. Production timing remained valid; the fixture incorrectly required different clock ticks. The two affected fixtures now own controlled clocks and explicitly advance between synthetic chunks. Strict ordering, exact timing milestones, SSE retention and privacy assertions remain in place. All 15 message-contract tests passed after the change, followed by a fresh canonical fast validation with 378 script tests and all fast gates passing. Published fixture source: `a7c0be4b06d430854ec721cc31ab57b8e2853d57`.

The complete local aggregate above predates this test-only refinement. The other 165 manifest entries, including all production gateway code, are unchanged. Exact-new-head Windows and Android CI remain pending; previous-head successes do not qualify the new revision automatically.

The single gateway skip is `No bindable LAN IPv4 interface available on this host`; resolved nonloopback addresses failed binding with `EADDRNOTAVAIL`. Loopback/all-interface checks passed. Initial environment failures were resolved by providing pinned dependencies to isolated subprocesses. The active-cancellation test's timer-before-dispatch interleaving was reproduced and its synchronization repaired while preserving its cleanup and deadline assertions.

The command-line remote lacked write credentials, so source commits were published through the connected repository API. That changed commit identities. Every published Git tree was checked for exact equality with its local source tree; the local-to-published mapping is retained in the task record. No production source changed during publication.

Focused host tests compile actual production policy/transport files with explicitly inventoried Android/framework boundaries. They are not a substitute for the complete Android build, Robolectric/Compose instrumentation or private-server and physical-device qualification.
