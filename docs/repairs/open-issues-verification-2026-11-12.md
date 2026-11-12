# Open issue verification — November 12, 2026

## Scope and source

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Requested branch: `fix/session-reliability-reporting-2026-11-12`, PR #838. This pass started from published head `ddbea3a98d8ab5c38fbfec6d6b2a5be789382e2e`, with main at `97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`. It used an isolated checkout and preserved the existing reporting, voice, status-evidence and gateway repairs.

The live inventory contained ten bug reports, #839–#848. All reports identify the older installed app source `d739321b2ac1eb9eb29675ad3c9b59b2d635a1c2`. Issue closure describes corrected and tested source behavior, not proof that an older installed APK contains the repair.

## Verified closures

Eight issues were closed as completed after the published repair's Android production/test compilation, full unit-test and coverage step, constrained-heap checks and test-result publication passed in [PR validation run 43281518258](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43281518258/job/131404145679). These observations are bound to `ddbea3a98d8ab5c38fbfec6d6b2a5be789382e2e`; APK packaging and the overall job were still running when the closure comments were written. Each issue has a separate resolution comment with the source and verification links.

| Issues | Corrected source defect | Regression evidence |
| --- | --- | --- |
| #839, #840 | Full GitHub check evidence survives argument normalization and bounded presentation. Truncated saved observations permit one bounded reread; omitted, skipped or wrong-head checks do not become successes. | `GitHubStatusEvidenceRecoveryTest`, `GitHubBoundRepositoryToolTest`, `GitHubStatusAuditRegressionTest`, `GitHubReadProjectionTest`; oversized 21/22-check responses exercise the real tool-to-evidence path. |
| #842, #843 | Empty recognition without retained words or an exception follows bounded retries without creating automatic bug incidents. Partial recognized words remain protected. | `VoiceFailureReportingTest.quietTurnsKeepBoundedRetryGuidanceWithoutCreatingBugReports` and `silenceWithCapturedWordsKeepsTheDraftAndReportsTheMissingFinalResult`. |
| #844 | Native URL-reader HTTP failures retain the status, source-access classification, phase and dispatch evidence instead of generic `APP_ERROR`. | `ReadUrlToolTest.sourceHttpRejectionsRetainStatusWithoutPublishingBodyOrUrl` and the actual tool-recorder regression in `IssueReportCollectorsTest`. An external HTTP 402 remains an external response. |
| #845–#847 | Known selected-model/privacy incompatibility is checked before voice capture and model/retrieval preparation. A later rejection preserves the draft and produces one typed incident. | `ChatRepositoryImplTest`, `OpenRouterPrivacyPreflightTest`, `VoiceEntryViewModelTest` and `VoiceFailureReportingTest` privacy-admission regressions. A compatible selected model/account is still required; privacy restrictions are preserved. |

## Additional repair: incomplete-task reports retain their cause

The newest report, #848, exposes a concrete reporting defect. The GitHub session emits `acceptance_verification`, a stop reason, a recovery action and required/verified/remaining criterion counts. The public incident projection omitted the acceptance stage and reasons and had no fields for the counts. A saved report's redelivery also omitted its retained failure details.

The follow-up adds a closed, bounded completion-context projection and carries the exact retained verdict into report redelivery. Unknown observations remain absent; zero is retained only when observed. Free-form task text, private criteria and provider response bodies are excluded. Retained primary failure causes keep precedence over a generic acceptance-stage label, and historical attempt facts are not presented as fresh dispatch observations.

The hosted reporting contract uses context schema 2 for this additional evidence. Context schema 1 remains unchanged so existing approved payloads, rendering, hashes and uncertain-send reconciliation remain valid. The service advertises support for both versions, and distribution readiness checks require both for a configured hosted service. Android already checks support before a dispatch marker or POST: it preserves an approved schema-2 report if an older service is configured. Native GitHub delivery retains its existing path. This source change does not deploy a reporting service or switch an approved report's route.

Behavioral regressions cover the actual incident collector, exact saved-report delivery, absent/zero/invalid counts, bounded enums, legacy approved bytes and digest reconciliation, and no-dispatch negotiation with an older reporter. The new source passed the canonical host gate (472 tests), the authentication/reporting suites (52 tests), and pinned ktlint 1.3.1 formatting. The source checkpoint records the validation state when this patch is published; subsequent Android results are recorded on PR #838.

## Issues that remain open

### #841 — microphone focus on the affected phone

The existing repair preserves `MICROPHONE_FOCUS_DENIED` and `audio_focus`, permits a bounded one-second session backoff before any PCM is captured, and closes capture resources before retry. The relevant recognizer and incident tests passed with the published source. The report does not identify the competing focus holder, and no successful focus acquisition or PCM capture on the affected ASUS device is available here. The issue remains open for that device evidence; suppressing the error or bypassing capture authorization would not resolve it.

Acceptance requires a branch build on that phone, a successful foreground voice turn with observed focus acquisition and PCM, and a background/foreground or interruption recovery check with retained capture diagnostics.

### #848 — the original unfinished GitHub task

The report contains a run hash and `GITHUB_TASK_INCOMPLETE`, but no failed criterion, stop reason or matching execution checkpoint. Its OpenRouter provider differs from the two older local-model status incidents; the available evidence does not establish the same cause. No newer uploaded capture was available during this pass. The reporting repair improves subsequent diagnosis and retained-report delivery, but it does not prove resolution of the original task. The issue remains open pending the matching checkpoint or a reproducible failure from the repaired build.

## Validation limits

The eight closures rely on already published source and its successful hosted behavioral tests. New context-schema changes require their own published-head compilation and regressions. Physical phone behavior and inaccessible private services are not validated by JVM fixtures. No branch rules, privacy restrictions, approved payloads or uncertain-write reconciliation requirements were weakened.
