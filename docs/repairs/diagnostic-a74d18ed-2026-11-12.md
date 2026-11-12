# Diagnostic a74d18ed: session recovery and reporting repairs

## Scope and source

This follow-up implements the actionable findings from diagnostic session
`a74d18ed-d2eb-43d2-bde1-cc82a48cff83` on
`fix/session-reliability-reporting-2026-11-12` (PR #838).
The implementation started from `e1a754336d291ee1ad72878078d7f854d3d0735e`.
The captured application was `956d50d320c9d2f4794a21eee6886b39eaa14ded`,
while the selected repository revision in the capture was
`97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`.
These are different revisions: the capture does not exercise the fixes below.

The archive's 20 section hashes and byte counts were verified. All 10,117 JSONL
records parsed; 470 chunks reconstructed into 27 verified logical records.
The capture retained five runs, 59 completed tool calls, and no tool mutations.
It contains application-wide events as well as the selected chat. Background
gateway/model and microphone events are not automatically attributed to that chat.
The archive and its raw user content are not committed to the repository.

## Findings and disposition

| # | Observed problem | Implementation or disposition | Remaining acceptance |
| --- | --- | --- | --- |
| 1 | Two provider-limit wake-ups stopped at `PROVIDER_WAIT_CHECKPOINT_CHANGED`. | Persist the accepted tool-receipt journal key inside the hash-bound continuation checkpoint. Resume loads that journal even when the current authorization revision changes because a provider-wait parent guard is installed. Current task, profile, permissions, fence, and exact evidence checks still apply. | Exercise a real rate-limit pause and process restart with the updated APK; verify completed tools are not repeated. |
| 2 | A later recovery stopped at `RESEARCH_RECOVERY_ACTION_CHANGED`. | Compare the semantic recovery policy rather than volatile UI/readiness observations. Changes to actual permissions, provider identity, accepted budget, destination, or recovery action still invalidate the saved action. | Device execution through the scheduler after a cooldown. |
| 3 | A failed run retained a resumable `WAITING_PROVIDER_LIMIT` checkpoint and the preceding 429 verdict. | Project terminal provider-wait state only onto the exact saved checkpoint, using the serialized checkpoint digest and current owner/fence. The compact failure summary prioritizes the terminal local guard and retains the old rate limit separately. | Room-backed race/process-death coverage and a new diagnostic export from the device. |
| 4 | 98 result reads and 98 command reads in about 125 seconds included reads faster than the advertised two-second delay. | Share in-flight and retained job observations across gateway client instances, honor `Retry-After`, bound retained data, and invalidate on configuration/network/runtime changes and continuation/cancellation writes. Cache passive accepted-command identity; keep explicit reconciliation fresh. | Live gateway traffic capture demonstrating the reduced request rate. The original body-free log cannot prove all reads were for one job. |
| 5 | Main-thread transcript work and frame stalls occurred, including a 128 ms transcript update and a 213 ms frame. | Remember the visible transcript slice, use a stable row content type, and derive viewport height and row visibility so layout-offset changes do not invalidate answer rows on every scroll frame. | Measure frame timings and scrolling on the affected device with realistic long messages. No frame-rate improvement is claimed from source inspection alone. |
| 6 | An early retry reached the local provider-limit precheck; countdown evidence was inconsistent. | Check provider cooldown before expensive preparation and share the admission decision with the wait path. Preserve recorded cooldown facts. The earlier branch fix `0057d4e1` already retains a rejected provider attempt's rate-limit cause without granting replay permission. | Compare actual request dispatch times with the next eligible timestamp on a real provider. |
| 7 | Recovery failures appeared as generic tool/chat failures, with missing session identity in some reports. | Classify exact application recovery guards as nonretryable `POLICY_STALE`, retain their exact reason in the user message, and bind outer preparation/recovery failures to their run and provider. The compact diagnostic summary separates the terminal guard from the old provider invocation and its retry/dispatch fields. Earlier branch fix `0057d4e1` already removes the unknown-model-exception-to-tool-failure fallback. | Inspect a fresh exported report and the existing report-service compatibility path. No new public issue-code schema is introduced for these guards. |
| 8 | The parent run's start time changed to its terminal time during recovery. | Keep the first `started_at` value when claiming or marking a resumed run as running. | Room/JVM and device timeline validation, including an initially null start time. |
| 9 | The accepted 256-call task limit was rendered as `NOT_RECORDED` even though 53 calls and 203 remaining were known. | Carry the accepted prompt contract through evidence, progress, checkpoint, checklist, and final report generation. Use the stricter accepted/observed ceiling and keep spent calls authoritative. | Check the full report after a durable resume; the 256/53/203 case has a focused regression. |
| 10 | Repository capabilities and lifecycle status were both blocked as if repository/revision data were missing. | Run schema-valid capabilities reads without invented repository arguments. Lifecycle status requires the actual operation identity or matching retained destination receipt. The supported read-only fixture preset marks an uncreated lifecycle operation conditional instead of fabricating it. | Real gateway/local repository operation receipts and fixture cleanup. |
| 11 | The diagnostic-capture interval allocated and freed roughly 4 GiB process-wide, with 53 GCs. | Bound shared polling retention and reduce repeated transcript work. Existing streaming diagnostic sinks and cached memory sampling remain in place. This capture does not identify the allocation owner and does not prove a memory leak. | Allocation profiling during a comparable capture; heap and GC measurements before further memory-specific changes. |
| 12 | Background availability checks returned `SELECTED_MODEL_NOT_LOADED`. | Confirmed that exact selected-model preparation and persisted reconciliation are already wired through New Chat, Home, Chat, and Voice. Preserve that path and its selected-model identity; do not replace a user's model or infer that a local-model warming preference disables gateway preparation. | Real selected-model load/readiness acceptance on the configured gateway. |
| 13 | Two microphone attempts reported audio-focus denial. | Already repaired on this branch by `76dcfcce`: output audio focus is advisory for foreground microphone capture; permission, foreground state, recorder initialization, and Android-silenced capture checks remain enforced. Preserve the existing behavior regressions. | Physical microphone test while another app holds output focus. |
| 14 | An application-wide LLAMA event reported `COMPLETION_REPAIR_BUDGET_EXHAUSTED` with HTTP 200. | Existing branch changes retain finish/usage/output-limit and attempt evidence. Keep bounded completion repair and retained output. This archive lacks the causal invocation needed to justify raising a budget or changing the model. | Correlated backend/gateway receipt and real generation before diagnosing the exact exhaustion cause. |
| 15 | A bare `NOT_MEASURED` item made a factual verification report look like an unsupported affirmative claim. | Preserve explicit unmeasured scope separately. Unsupported affirmative statements remain unresolved, and unfinished task acceptance criteria remain unfinished. | End-to-end report after a resume, with both measured facts and explicitly unmeasured scope. |

## Safety and concurrency properties

- Completed tool receipts remain the source of truth. A refreshed policy or lost
  response does not authorize replay of a write.
- Restoring the accepted journal identity does not restore old permissions.
  Current authority, protected-source permissions, task identity, and checkpoint
  digest still gate recovery.
- Terminal checkpoint updates must match both the exact previously observed
  serialized payload and its current owner/fence. A late failure cannot overwrite
  a newer checkpoint or mark a successful newer attempt failed.
- Shared polling is scoped by connection identity and configuration. Inference,
  cancellation, and tool-continuation mutations are never cached or replayed by
  the read coordinator.
- Diagnostic summaries do not borrow the earlier provider invocation, dispatch
  state, or retry authorization to fill gaps in a later local recovery failure.
- Branch publication reconciles the current remote head and uses an ordinary
  non-force push. Existing microphone, reporting, and review recovery changes are
  retained.

Legacy research recovery actions migrate only when their complete saved policy
hash still matches the current complete legacy policy. Older provider-wait
checkpoints that never retained a receipt-journal key cannot reconstruct that
missing identity safely. Those checkpoints retain their strict evidence checks
and may still require explicit reconciliation; the repair does not guess a journal
or replay completed tools.

## Validation record

Validation is recorded in
[`diagnostic-a74d18ed-2026-11-12-task.json`](diagnostic-a74d18ed-2026-11-12-task.json).
Focused Kotlin/JVM regressions exercise production policy/report/coordinator
logic. Where a host harness substitutes Android or persistence services, that is
component coverage, not an Android build or physical-device result.

| Check | Observed result |
| --- | --- |
| Canonical host gate | **473 tests passed**, including policy, inventory/schema, dependency catalog, secret, regex, resource and whitespace checks. |
| Failure classification and compact summary | **20 JVM tests passed**, including guard-origin rejection and retained invocation ownership. |
| Integration budget, prerequisites and evidence | **71 JVM tests passed** against 56 current pure production classes and explicit service/DTO boundaries. |
| Gateway polling and selected-model preparation | **61 JVM tests passed**, including ten new polling behaviors and 23 existing selected-model preparation tests. |
| Recovery checkpoint, policy, retry and throttle helpers | **21 JVM tests passed** against actual production helper sources without Android stubs. |
| Gateway diagnostic protocol | **28 Python tests passed** using the repository's hash-pinned dependencies. |
| Kotlin formatting | **56 changed Kotlin files passed** ktlint 1.3.1. |
| Gateway package source | **218 file hashes matched** the canonical source manifest and its pinned local source revision; publication rebinds this to the identical published source tree. |
| Full Android compilation and Room/Robolectric integration | **Not run locally**; the Android SDK/Gradle environment is unavailable. Added persistence, ownership-race, scheduler and continuation tests require CI. |
| Physical-device and real-service acceptance | **Not measured**; see the finding-specific remaining acceptance above. |

The [evidence logs](evidence/diagnostic-a74d18ed-2026-11-12/) retain these results.
The fast host test log includes simulated downloader failures and device-result
fixtures; those lines are tests of the tooling and are not actual device results.
The first per-worktree fast checks found the expected stale generated audit
inventory. After regeneration on the combined source, the canonical gate passed.

The canonical host gate is `bash scripts/validate-local.sh fast`. Final source
integration also checks Kotlin formatting with pinned ktlint 1.3.1 and regenerates
the architecture index and diagnostic protocol projection when their source
changes require it. Hosted Android compilation, full unit tests, and review APK
checks must be associated with the final branch revision before being counted.

## Findings not supported by this capture

The retained tool calls all completed. The capture does not establish a GitHub
transport outage, a leaked fixture repository, a workflow pagination loop, a
wrong-provider dispatch, or a crash/ANR. Pagination cursors advance normally.
Zero visible response text for a tool-call-only round is not itself an error.
These observations are not promoted into additional code changes or claims of
device acceptance.
