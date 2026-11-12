# Open issue verification — November 12, 2026

## Scope and source

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Requested branch: `fix/session-reliability-reporting-2026-11-12`, PR #838. This pass started from published head `ddbea3a98d8ab5c38fbfec6d6b2a5be789382e2e`, with main at `97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`. It used an isolated checkout and preserved the existing reporting, voice, status-evidence and gateway repairs.

The initial live inventory contained ten bug reports, #839–#848. Those reports identify the older installed app source `d739321b2ac1eb9eb29675ad3c9b59b2d635a1c2`. Seven more reports, #849–#855, arrived between 19:24 and 19:35 UTC while validation was running and were added to this pass. They identify app commit `956d50d320c9d2f4794a21eee6886b39eaa14ded`, the earlier validation merge for the published baseline. Issue closure describes corrected and tested source behavior, not proof that an installed APK contains a later repair.

## Verified closures

Eight issues were closed as completed after the published repair's Android production/test compilation, full unit-test and coverage step, constrained-heap checks and test-result publication passed in [PR validation run 43281518258](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43281518258/job/131404145679). These observations are bound to `ddbea3a98d8ab5c38fbfec6d6b2a5be789382e2e`; APK packaging and the overall job were still running when the closure comments were written. Each issue has a separate resolution comment with the source and verification links.

| Issues | Corrected source defect | Regression evidence |
| --- | --- | --- |
| #839, #840 | Full GitHub check evidence survives argument normalization and bounded presentation. Truncated saved observations permit one bounded reread; omitted, skipped or wrong-head checks do not become successes. | `GitHubStatusEvidenceRecoveryTest`, `GitHubBoundRepositoryToolTest`, `GitHubStatusAuditRegressionTest`, `GitHubReadProjectionTest`; oversized 21/22-check responses exercise the real tool-to-evidence path. |
| #842, #843 | Empty recognition without retained words or an exception follows bounded retries without creating automatic bug incidents. Partial recognized words remain protected. | `VoiceFailureReportingTest.quietTurnsKeepBoundedRetryGuidanceWithoutCreatingBugReports` and `silenceWithCapturedWordsKeepsTheDraftAndReportsTheMissingFinalResult`. |
| #844 | Native URL-reader HTTP failures retain the status, source-access classification, phase and dispatch evidence instead of generic `APP_ERROR`. | `ReadUrlToolTest.sourceHttpRejectionsRetainStatusWithoutPublishingBodyOrUrl` and the actual tool-recorder regression in `IssueReportCollectorsTest`. An external HTTP 402 remains an external response. |
| #845–#847 | Known selected-model/privacy incompatibility is checked before voice capture and model/retrieval preparation. A later rejection preserves the draft and produces one typed incident. | `ChatRepositoryImplTest`, `OpenRouterPrivacyPreflightTest`, `VoiceEntryViewModelTest` and `VoiceFailureReportingTest` privacy-admission regressions. A compatible selected model/account is still required; privacy restrictions are preserved. |

## Additional repair: incomplete-task reports retain their cause

Report #848 exposes a concrete reporting defect. The GitHub session emits `acceptance_verification`, a stop reason, a recovery action and required/verified/remaining criterion counts. The public incident projection omitted the acceptance stage and reasons and had no fields for the counts. A saved report's redelivery also omitted its retained failure details.

The follow-up adds a closed, bounded completion-context projection and carries the exact retained verdict into report redelivery. Unknown observations remain absent; zero is retained only when observed. Free-form task text, private criteria and provider response bodies are excluded. Retained primary failure causes keep precedence over a generic acceptance-stage label, and historical attempt facts are not presented as fresh dispatch observations.

The hosted reporting contract uses context schema 2 for this additional evidence. Concurrent follow-up through `15e07ae05f1ba9a1c42edb10313d034f5ad098c5` adds context schema 3 for review extensions. Original context-1/context-2 payloads, rendering, hashes and uncertain-send reconciliation remain unchanged. Retained extended-context-2 approvals require both version-2 wire support and version-3 semantics without rewriting approved bytes. The service advertises `[1, 2, 3]`, and Android checks support before a dispatch marker or POST. Native GitHub delivery retains its existing path. These source changes do not deploy a reporting service or switch an approved report's route.

Behavioral regressions cover the actual incident collector, exact saved-report delivery, absent/zero/invalid counts, bounded enums, legacy approved bytes and digest reconciliation, and no-dispatch negotiation with an older reporter. The new source passed the canonical host gate (472 tests), the authentication/reporting suites (52 tests), and pinned ktlint 1.3.1 formatting. The source checkpoint records the validation state when this patch is published; subsequent Android results are recorded on PR #838.

## Issues that remain open

### #841 and #852 — microphone capture blocked by output focus

The earlier repair preserved `MICROPHONE_FOCUS_DENIED`, its `audio_focus` stage and bounded retry. New report #852 confirms that typed path reached the phone, but still shows no microphone samples. A deeper source review found a concrete admission defect: `AndroidVoiceCapture` treated denial of output audio focus as denial of microphone input and threw before constructing `AudioRecord`.

[Android's input-sharing documentation](https://developer.android.com/media/platform/sharing-audio-input) distinguishes output-focus requests from the system's automatic input-priority policy. The follow-up makes output focus advisory while retaining the existing microphone permission, foreground lifecycle, cancellation, recorder initialization and recording-state checks. It detects `AudioRecordingConfiguration.isClientSilenced` before forwarding samples, stops that capture and reports the existing supported `VOICE_MICROPHONE_BUSY` public category. A missing recording configuration remains unknown. Owned playback still stops before listening, and an acquired focus lost during capture still pauses the session.

Regressions exercise the actual Android capture class with denied focus and permitted recording, permission rejection, recorder failure, system-silenced input and focus-loss cleanup. They also cover the recognizer's non-retryable silencing failure and public-code compatibility. Hosted execution of the new tests is required before claiming them passed. A physical ASUS voice turn and interruption/background/foreground recovery remain device qualification; the source repair does not invent that result.

### #848 — the original unfinished GitHub task

The report contains a run hash and `GITHUB_TASK_INCOMPLETE`, but no failed criterion, stop reason or matching execution checkpoint. Its OpenRouter provider differs from the two older local-model status incidents; the available evidence does not establish the same cause. No newer uploaded capture was available during this pass. The reporting repair improves subsequent diagnosis and retained-report delivery, but it does not prove resolution of the original task. The issue remains open pending the matching checkpoint or a reproducible failure from the repaired build.

## Reports that arrived during validation

| Issues | Observed evidence and source action | Remaining evidence |
| --- | --- | --- |
| #849 | LLAMA reported `COMPLETION_REPAIR_BUDGET_EXHAUSTED` after HTTP 200. The combined branch preserves bounded backend attempt/repair receipts even when token usage is unknown. | Matching saved task and backend attempt/output/usage receipts; the public report does not identify why generation exhausted its allowance. |
| #850 | LLAMA reported a generic chat failure with a run hash, but no stage, invocation or typed cause. | Matching saved failure or diagnostic export. Informational gateway/tool events do not identify the cause. |
| #851 | OpenRouter reported `GITHUB_TASK_INCOMPLETE` without acceptance facts. The context-schema repair preserves those facts for subsequent reports and saved-report delivery. | Matching task/acceptance checkpoint identifying the failed criterion and stop reason. |
| #852 | Typed focus denial reproduced on the newer installed build. | See the concrete output-focus/input-admission repair above and its pending hosted tests. |
| #853 | OpenRouter returned HTTP 429, explicit remote acceptance `NO`, and a 30,156 ms cooldown. Numeric provider codes previously became generic `CHAT_FAILED` when no resumable wait existed. The collector now retains the existing `PROVIDER_RATE_LIMITED` category only from those typed facts. | Successful provider recovery or a new failure receipt. Correct reporting does not remove the provider's throttle or authorize another dispatch. |
| #854 | An exception at `session.streamRound` used a tool classifier's `TOOL_FAILURE` fallback even with no tool execution. The model boundary now retains local `PROVIDER_INVOCATION_FAILED`, bounded exception-class metadata and explicit no-replay/no-continuation facts. Public reports use the existing compatible `CHAT_FAILED` category and model stage. Saved GitHub receipt/acceptance rendering still runs without a new model or tool call. | Matching original exception/task evidence; correcting its domain does not establish why the model call failed. |
| #855 | Generic chat failure has every context field unavailable. | Matching saved failure/export; adjacent events cannot supply missing run or provider identity. |

The model-boundary regressions cover synchronous and asynchronous exceptions, zero unintended tool calls, retained completed-write receipts, no message-derived retry or cooldown, and preservation of an earlier unknown outcome. Rate-limit regressions reproduce the reported 30,156 ms duration and reject ambiguous, string-typed, tool-stage or reconciliation evidence. Neither repair expands the public reporting contract or changes approved payload bytes.

## Follow-up validation checkpoint

The consolidated follow-up passed `bash scripts/validate-local.sh fast` (473 tests plus repository, resource and policy checks) and all 56 authentication/reporting tests. Independent source review checked provider retry authority, retained GitHub receipts, output-focus/input-admission separation, capture silencing and cancellation races. Pinned ktlint 1.3.1 checks all changed Kotlin files before publication. Android compilation and the new behavioral tests must still run on the published head; later results and any resulting closures are recorded on PR #838. Superseded CI runs are recorded as cancelled, never as passing evidence.

## Validation limits

The eight closures rely on already published source and its successful hosted behavioral tests. New context-schema changes require their own published-head compilation and regressions. Physical phone behavior and inaccessible private services are not validated by JVM fixtures. No branch rules, privacy restrictions, approved payloads or uncertain-write reconciliation requirements were weakened.
