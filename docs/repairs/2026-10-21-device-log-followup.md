# Device-log follow-up: Research and Combined recovery

## Evidence and scope

Reviewed all 5,843 JSONL records in `app-diagnostics-24832655322261570.jsonl`: 3,147 events and 2,696 continuation records. The export identifies version 0.9.55.0 (124), commit `8dc674faa883bb33a76c47ae9ac501d79f6999a8`, the PR #728 test-merge revision observed during this review. Implementation starts from branch head `d25ee552085bd12f002b831c5c521d3981c7b28c`.

The log contains 13 archive indexing failures, 33 automatic examinations blocked by an unselected examiner, two 33-slot provider tool batches rejected before dispatch, two explicit recovery requests blocked by source checks, and repeated frame-time warnings. Provider 429s, interrupted connections, DNS failures and publisher HTTP 403s are also present. Issue flags are symptoms and repeated measurements, not independent bug counts. No raw log content or user queries are committed.

## Changes

- **Recovery authorization:** Native marketplace installations now validate their registry, enabled profile, provider/operation switches, endpoint and credential references. They no longer require nonexistent MCP binding rows. Usage counters and cooldown timestamps do not invalidate a grant. Connected search validates its own provider grant independently of the built-in search toggle; explicit MCP grants retain their approved-read and connection-revision checks. Rejected recovery diagnostics identify the affected tool names.
- **Archive retry:** Failed ingestions retain their source in the durable outbox and use exponential backoff from 30 seconds to one hour. Repeated progress saves of the same source respect that backoff, including after restart. Retry failures update receipts with a classified cause and exception type. Identity conflicts, unavailable retained bodies and database/storage failures are distinguishable. Retry honors both the originally recorded archive eligibility and current Research settings. It cannot re-enable a disabled archive feature.
- **Provider batches:** A streaming slot overflow gets one smaller-batch repair attempt when there is remaining round/call allowance and no visible output. All calls in the rejected batch remain undispatched. Another overflow falls back to the existing bounded finalization behavior. Parser-observed calls count toward the run allowance.
- **Automatic Examine:** Missing/disabled/ambiguous examiner selections are recorded as configuration waits before invoking the coordinator. One summary replaces per-study exception noise; queued studies remain retained until a relevant configuration change. No profile or consent is chosen implicitly.
- **Evidence storage:** Timestamp-only progress saves no longer create full duplicate immutable evidence pages. Work counters, phase, sources, review state and authority changes still produce distinct revisions. Manifest timestamps restore the original capture ordering.
- **Android compilation:** Native adapter setup, dynamic tool admission and rejected-batch recovery now have separate methods, reducing the size of the main resolver and execution coroutines. The DEX guard now includes both AgentRunner and AgentToolResolver, including their generated classes. Previously it checked only ChatRepositoryImpl dispatch. Running the expanded guard against the previous APK reproduces the logged failures: 18,670 units for executeLoop and 17,244 for resolve, above the exclusive 16,383-unit limit.

## Validation

- **Android JVM:** 193 targeted tests passed across 13 suites, with zero failures, errors or skips. Coverage includes recovery authorization, tool admission and overflow repair, retained evidence, archive retry, examiner configuration and Combined/research delivery. These are fresh results for this follow-up; the earlier full-suite results in PR #728 are historical.
- **Fresh compilation:** `:app:dexBuilderDebug` and `:app:testDebugUnitTest` completed with Kotlin incremental compilation and the build cache disabled. The sandbox's test JVM used an explicit Byte Buddy agent for MockK and inherited the available network settings through local Gradle init files; no test checks were bypassed.
- **DEX guard:** 1,133 methods passed against the exclusive 16,383-unit ceiling using the fresh Android-transformed project DEX files. The maximum remains `ChatRepositoryImpl.delegateToProfile` at 16,156. A DEX-only validation archive was used; no installable APK was packaged in this follow-up.
- **Formatting:** All 12 changed Kotlin files passed ktlint 1.3.1; `git diff --check` passed.

| Method flagged by the device log | Previous APK DEX units | Fresh DEX units |
| --- | ---: | ---: |
| `AgentRunner.executeLoop` | 18,670 | 15,890 |
| `AgentToolResolver.resolve` | 17,244 | 14,564 |

The expanded guard rejects the previous APK and accepts the rebuilt code. This follow-up did not rerun the full repository suite, native packaging, Android lint or device instrumentation.

## Limits

The original archive warnings omitted the underlying exception. They establish repeated indexing failure but do not establish that every failure was an identity conflict or missing body. The new classification and regression cases address observable retry defects without guessing which unrecorded exception occurred on the phone. Conflicting strong identifiers remain separate for review; saved source evidence is not discarded or silently merged.

Network availability, upstream rate limits and publisher access rules cannot be guaranteed by these changes. Failed acquisition remains visible and does not become verified evidence. Physical-device frame timing, process-death behavior and live provider execution require a new device run. The DEX check establishes compilable method size, not measured frame-rate improvement.
