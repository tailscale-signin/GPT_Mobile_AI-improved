# Stability design implementation — 24 October 2026

This branch implements the Combined, Research and benchmark repair designs supplied on 23 October. It builds on the native initialization, CPU recovery, tool omission, provider diagnostics and rendering repairs already present in the repository, and preserves the provider and home-screen work from the existing design branch.

The implementation has three authorities with different jobs:

| Area | Durable authority | Meaning |
|---|---|---|
| Accepted Research work | Versioned atomic execution record | Immutable accepted intent, current attempt and generation, pending scheduling action and operational receipts survive a restart together. |
| Conversation and Combined delivery | Chat Room database and sealed synthesis snapshot | Saved messages, ownership fences, selected contribution revisions and final writer input remain bound to the accepted turn. Shared accepted-policy fragments resolve to their exact original bytes. |
| Benchmark execution | Benchmark Room database, version 3 | Frozen ordered batch, attempts, terminal cases and next queue position remain authoritative if a preferences mirror or derived scoreboard fails. |

## Combined delivery

The final writer is selected before dispatch from the user's accepted profiles. Its immutable request plan includes the full final prompt, editorial system, selected history, captured document and attachment context, route identity, protocol and output allowance. Known context capacities participate in admission; unknown capacities are explicitly recorded as unverified. The provider serializer still checks its actual request and cannot silently reduce the frozen output allowance.

Reported reasoning and output-limit observations influence future planning. Each usage receipt carries the identity of the actual served route, including model and backend when reported. A delayed receipt cannot teach an unrelated route merely because the requested alias resolved differently in another invocation. A partial paragraph never establishes that an allowance was sufficient for a completed answer.

Complete contributions are retained once with a shared immutable content index. Scoped claims preserve material qualifications and exact source spans. Canonical claims and aliases resolve through all accepted occurrences, and tables retain header/value relationships. Lexical coverage remains a local hint; it is not reported as complete semantic or factual verification.

Collection decisions are durable and attempt-specific. Continue acknowledges the current failed attempts; a later failed retry requires its own decision. A failed retry can retain an earlier usable contribution. Retry and freeze share the chat gate, preventing a late completion or repeated tap from silently replacing frozen evidence. Explicit final retries receive a new root budget, dispatch identity and revision while retaining the immutable original task, evidence, question obligations and captured context.

The main answer, copy and normal export use the answer-only projection. Source profiles remain accessible through their tabs. One grouped failure dialog exposes targeted retry, remove/Undo, Continue and primary-selection actions. Nonrecoverable configuration failures and cooldowns constrain those actions.

Detailed mapping: [Combined implementation](Combined_Mode_Design_2026-10-24.md).

## Research execution and recovery

Acceptance saves the complete policy payload, task text, scope, source target, participants and requested models with the first attempt and scheduling action. Execution transitions carry immutable attempt and generation identities. Each accepted run records its ownership before waiting, including recovery paths that reuse a saved answer without a new physical request.

Resource admission distinguishes the local runtime, remote endpoint concurrency and benchmark measurement isolation. Waiting is observable and does not spend a provider attempt or count as response latency. There is no implicit 60-second failure for a legitimate resource wait. An explicitly accepted task deadline still applies. Benchmark isolation yields after a completed case has been durably saved and local cleanup is acknowledged.

A stale attempt or changed accepted configuration is rejected before physical dispatch. Startup reconciles saved execution against the matching attempt's run and delivery records. It cannot inherit an older attempt's success. Dispatched work whose outcome is uncertain requires reconciliation before a new attempt; startup does not blindly resend it.

Research completion requires retained readable evidence and a matching saved delivery receipt. Contributor completion or a successful WorkManager invocation alone does not satisfy that contract. No-evidence failure, retained-evidence delivery failure, cancellation, configuration blockage, interruption, partial delivery and completion remain distinct states.

Small delivery packets preserve a complete verified quotation when the ordinary excerpt plus metadata would exceed the accepted packet limit. Source identity and provenance remain attached, and the size limit remains enforced. Superseded or deleted attempts cannot persist late partial answers or delivery receipts.

The analysis budget is task-wide. For example, a 90-source target remains one shared target across contributors. Discovery attempts, unique readable sources, analysis assignments and terminal work are separate counters. Failed work is terminal but is not reported as successful work.

Required Research intent governs the initial planner, schema repair, fallback and final validation. Scientific report structure can be inferred from the accepted request and explicitly overridden. Existing source scope and permission rules continue to apply during acquisition, evidence sharing and resume.

Detailed mapping: [Research implementation](Research_Stability_Design_2026-10-24.md).

## Benchmark execution

Each selected profile has an ordered durable queue item and an isolated child attempt. A failed or timed-out profile preserves its recorded cases and allows eligible later profiles to run. Parent Stop remains a batch cancellation. Stop and Skip identify the current session and ordinal so a stale action cannot affect a newer profile.

Each scored case holds its measurement lease through its durable checkpoint and bounded cleanup. Native cleanup checks the worker operation and open conversation independently of the outer admission lease. Unacknowledged native cleanup blocks subsequent local work; it is not converted into a successful cleanup receipt. Independent remote candidates can continue when the recorded disposition permits them.

Restart resumes untouched work from the frozen manifest. A terminal case saved before a queue-position update is recognized without replay. Retry Failed creates a linked new batch. Storage failures pause execution with retained history, and failures in preferences mirrors or derived scores cannot erase canonical results or crash a background publishing coroutine.

Suite 8 separates answer accuracy, valid delivery and availability, while suite 7 remains frozen for historical comparisons. Comparisons require matching population and fixture provenance. Token throughput retains the source and confidence of its measurements; estimates cannot masquerade as provider or native token counts. Final measurements replace earlier estimates. Sequential phase timings, queue wait, cleanup, worker/session identity, admission lease and actual runtime/backend data are retained when available. Unsupported engine, driver and actual offload measurements remain unknown.

Detailed mapping: [Benchmark implementation](Benchmark_Repair_Design_2026-10-24.md).

## Diagnostics and migration safety

HTTP-200 error payloads, tool-choice mismatches, invalid output records, reasoning-only responses, output truncation, DNS/connection failures, rate limits, resource-deadline expiry and local initialization failures have distinct categories. Clean EOF that violates the answer protocol differs from a recorded output-limit termination. Usable committed passages remain available.

Canonical accepted-policy storage retains schema, digest and ownership metadata. Public readers reconstruct the byte-exact original synthesis snapshot so its existing seal remains valid. Legacy inline snapshots are readable. A missing or corrupt reference blocks execution with a recoverable saved-policy error; observable history retains readable messages and valid siblings. Backup validation must reject incomplete policy data before replacing destination data. Temporary chats and deletion retain their existing privacy semantics.

Diagnostic exports distinguish disabled, empty, partial and failed capture and report loss reasons separately. Operational recovery receipts remain available under the functional storage policy, without enabling verbose content capture. Worker outcomes identify scope and processed-item counts, including no-op work. Small event-order reversals do not by themselves establish a wall-clock discontinuity.

## Validation

The final automated run completed successfully on 24 October 2026. All 3,194 source, configuration and fixture fingerprints remained unchanged from the start of the full run through APK verification.

| Check | Result |
|---|---|
| Kotlin, KSP, Room and Hilt compilation | Passed for the default debug configuration. |
| Full Android unit suite and JaCoCo | 4,335 tests across 721 classes: 4,333 passed, zero failures or errors, and two tests intentionally deferred to the constrained-heap configuration. Coverage report generated. |
| 256 MiB recovery/export gate | All seven tests passed, including both deferred large-corpus tests. The fixtures retain or export more than 40 MiB of evidence without loading the whole conversation/evidence tree. |
| Android lint | Completed with zero errors. It reports 3,020 warnings and 25 hints, predominantly missing translations and unused resources; this change does not claim a warning-free repository. |
| Kotlin formatting | All 196 changed Kotlin files passed ktlint 1.3.1. |
| Native build | CPU, Vulkan and OpenCL runtime build passed for ARM64 and x86-64. |
| Debug APKs | ARM64, x86-64 and universal APKs assembled successfully. Runtime inventory/provenance, MediaPipe/ObjectBox JNI definitions, 16 KiB host-library alignment, telemetry exclusions and package-size checks passed for all three. |
| Packaged dispatch methods | Largest guarded method is 15,824 DEX code units, below the exclusive 16,383-unit gate, in each APK. |
| Database history | Chat schema 45 and benchmark schema 3 were generated and are included with their migration and restoration regressions. |
| Repository Python checks | 158 script tests and the GGUF prefill-governor test passed. |
| Gateway checks | 190 Python contract tests and seven JavaScript tests passed. |
| Static preflight | Resource/icon validation, native-runtime provenance, Android regex compatibility, unified reliability inventory and whitespace checks passed. |

The normal unit run and constrained-heap run overlap in five tests; their counts must not be added as independent test cases. The two normal-run skips were the large export and retained-evidence tests, both of which passed under the required 256 MiB configuration.

The full run also caught and verified repairs for frozen-system-prompt drift, small handoff packets losing their only source, generic backup fixtures without agent tables, and late receipt rejection after deletion. Test fixtures now reflect the accepted clean-export, active-final ownership, per-workload deadline and canonical serialization contracts. The interrupted-request dialog test uses the repository's native Robolectric render configuration and proves real scrolling, checkbox acknowledgment and one-time authorization.

The default app version remains `0.9.56.1` / code `127`. `BuildConfig.APP_COMMIT`, also exported in diagnostics, identifies the exact installed revision; the version number alone does not identify this change.

## Device and live-provider acceptance gates

These checks require the target device or an explicitly authorized live-provider run. A successful build or unit suite does not establish these outcomes:

1. On the ROG Phone 9 Pro, block a GPU probe, verify the recorded native stage and deadline, confirm worker termination, and verify that recovery uses a fresh CPU worker with no GPU initialization.
2. Replay the long queued-provider conversation while typing, scrolling, switching profile tabs and opening the grouped failure dialog. Capture frame traces and compare the frequency and duration of frames over 100 ms.
3. Exercise benchmark Stop, Skip, background notification controls, process death and resume with a real local runtime. Verify worker cleanup and persisted queue position before the next local trial starts.
4. Inspect light/dark theme, large fonts and TalkBack behavior on the actual device, including table reading order and dialog focus.
5. Verify served-model reasoning controls and advertised output bounds against the selected live endpoints. The client records unverified capacity and control behavior; fixture tests cannot certify remote provider guarantees.

No live provider generations, phone performance measurements or GPU driver qualification are claimed by this implementation record.
