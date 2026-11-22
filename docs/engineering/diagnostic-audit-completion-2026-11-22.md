# Diagnostic audit completion — November 22, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base branch: `main`; observed base SHA: `c023735c02368fef4c748cf7be97b9fcfe2cd513`.
Shared remote branch: `fix/diagnostic-execution-completion-2026-11-22`.
Inherited concurrent-worker commit: `2636fb1c4fa68f9a9f74c8e00a015496bc5d9a6b`.
Isolated local integration branch: `work/audit-completion-2026-11-22`.

## Intent and concurrency

Implement every remaining repair from the 15-finding diagnostic audit. Preserve
concurrent work, push a draft PR, pass current-revision validation, merge with an
expected-head fence, and publish only after exact-main verification. Remote branch
and main revisions are rechecked before integration, push, and merge. No force
push, dropped commits, weakened acceptance gates, or unsafe replay is permitted.

## Repair ownership

- F01, F02, F05, F07: persistence, comparison, expired admission, acceptance.
- F03, F04, F08, F11: provider errors, continuation evidence, saved waits.
- F06, F09, F15: search, argument contracts, diagnostic correlation/deadlines.
- F10, F12, F13, F14: Gateway reachability, rendering, capture query/phases,
  recovery coroutine size; integration and publication.

The inherited commit already implements overlapping fixes. Each area is checked
against the original acceptance conditions and receives only the missing changes.
Device performance, real account credits, and physical network availability must
remain explicitly separate from source/regression/release evidence.

## Implemented acceptance map

| Finding | Implemented behavior | Regression evidence |
| --- | --- | --- |
| F01 evidence persistence | Atomic typed stored/duplicate/rejected/conflict outcomes; original encrypted bytes and current owner preserved | ProtectedSynthesisCheckpointTest; GitHubAuditEvidenceTest; public conflict contract |
| F02 comparison coverage | Exact authority/repository/SHA/query coverage survives duplicate earlier pages; deliberate refresh starts a new generation | GitHubAuditEvidenceTest four-page, repeat, refresh and foreign-scope cases |
| F03 credits | Semantic billing classification shared by HTTP and Responses/chat SSE; no unchanged transient wait | ProviderStreamFailureTest; OpenAIRecoveryTransportTest; failure/report projections |
| F04 upstream errors | Provider cause, actual status, response/request correlation, observed output and usage survive the transport/assembler/report path | ProviderStreamFailureTest; OpenAIRecoveryTransportTest |
| F05 expired admission | Saved task admission checks accepted contract, timing and authority before queue restore; explicit bounded renewal excludes uncertain writes, pending approval and verification-only wakes | GitHubTaskAdmissionTest; execution-limit and task-continuity regressions |
| F06 search continuation | Exact native query/frontier recipe survives compaction; guarded read continuation and intent-aware replanning | GitHubSearchFrontierTest; GitHubNativeSearchContinuationTest; GitHubRemoteContinuationTest |
| F07 empty acceptance | Distinct applicability/contract/plan/extraction/coverage observations and original primary cause; zero-of-zero never means completion | GitHubRunAcceptanceTest; closed report contract fixtures |
| F08 no progress | Bounded durable repair retains continuation attempt, output/token allowance, observed progress and stop predicate | GitHubOutputContinuationSessionTest; ExecutionCompletionDiagnosticsTest |
| F09 arguments | Native typed search validation reports exact safe field/type/rule; planner schema and public capability gate agree | GitHubSearchArgumentsTest; GitHubSearchValidationPipelineTest |
| F10 reachability | TCP probes share only exact service and network generation; independent readiness, reconnect and actionable offline guidance; read-only host inspection | LocalReachabilityProbesTest; Gateway availability tests; Windows connection diagnostic tests |
| F11 saved waits | Durable provider scope/deadline/checkpoint and explicit refusal/scheduler outcomes retained from the inherited implementation | ProviderWaitRecoveryTest; ProviderWaitSchedulingResultTest; transport fixtures |
| F12 rendering | Unchanged turn message lists/runtime maps retain identities; unrelated progress does not replace row inputs; per-row layout observation | ChatRunProjectionTest; matched device workload remains unmeasured |
| F13 capture | Room migration 51→52 adds chat/timestamp index; direct lexicographic seek preserves ties/high-watermark; separate phase counts and elapsed times | DiagnosticDatabaseSourceTest; RoadmapMigrationMatrixTest; DiagnosticCaptureTimingsTest |
| F14 recovery size | Non-inline selection, inspection, publication and notification methods retain serialized ownership; packaged recovery DEX gate enforces ART limit | GatewayCoordinatorRecoveryTest; packaged check_chat_method_size.py; parser regressions |
| F15 correlation | Process/session/worker operation aliases count one incident; common Network/MCP identity; only evidenced owned deadlines are actionable | DiagnosticIncidentTrackerTest; DiagnosticCrashCorrelationTest |

## Local validation and canonical gates

- Exact-source Kotlin 2.4.20 focused harness: 39 tests passed. Root rendering,
  reachability and timing helper tests run without Android stubs. The diagnostic
  parser harness uses narrow compile-only boundaries for unrelated Android APIs;
  full Android behavior remains gated by the repository's real Gradle suite.
- Host script suite: 603 tests passed. Canonical Gateway checks passed, including
  all host-executable Python suites and the seven mutation-session transport tests;
  platform-only checks remain explicitly identified for CI.
- Host SQLite: 10,000 rows retrieved in exact newest-first/tie order using the
  composite index and lexicographic seek, with no temporary sort. This is not a
  SQLCipher/physical-phone performance claim.
- Packaged-method parser and release trigger/scope regressions: 30 tests passed.
- Full authentication/report discovery: 195 tests passed, with no skips; the
  targeted reporting-route preflight and contract suite also passed (175 tests). The latest published build uses native GitHub reporting; no hosted
  reporting service is configured. A configured service must advertise both new
  diagnostic capabilities before release preflight succeeds.
- Timeout identity: nine focused JVM tests passed, covering inherited retry IDs,
  dispatcher changes, sibling separation and parent cancellation without a false
  owned-deadline event.
- Gateway host/control targeted suite with an isolated, publisher-hash-verified
  PowerShell 7.4.6 runtime: 19 tests passed; one unrelated packaged-QR check was
  skipped locally. Both PowerShell checks executed. CI exposed a duplicate common
  parameter in the test double; the regression was reproduced, the double fixed,
  and action/enabled/port/profile assertions strengthened. Production containment
  correctly retained unknown status when that test command failed.
- Kotlin formatting, Android resources, regex preflight and secret/CI-policy
  checks passed on the inspected edits. Generated inventories are refreshed after
  integration; full host/Gateway and current-head CI results are recorded in the PR.

The actual Room 52 schema was generated by KSP in draft validation run
`45049263993`, job `137290545008`, from PR head `aa06662` merged with base
`c023735`. The lossless export is committed with regenerated build facts; its
SHA-256 is `988c0d1c604bac252ef0b54b399c8a5203445c1dfa95eeef32589a1b6d6ddb58`
and Room identity is `8852c48e3d94209ba176f81b4046c039`. The unchanged mandatory
schema check now runs immediately after KSP, so drift is reported before expensive
compilation. No test or release gate is removed or weakened.

## Release candidate

Android **0.9.66.13** (build **147**) and Gateway **18.1.1** use one explicit
publication request. Preserve the scoped qualification disclosures in the release
notes. Recheck the remote branch and base, qualify the actual PR head, then qualify
the exact merged main commit and published artifact identities.

## CI integration follow-ups

- The Windows profile observation uses separate network-category and firewall-
  profile vocabularies. Domain, Any, mixed profiles and NotApplicable retain their
  meaning; empty or unrecognized rule scope remains unknown/partial and is shown
  explicitly. Real PowerShell and normalization regressions cover these cases.
- Audit unit compilation on `2fb4f44` identified two inherited test API mismatches.
  The no-record DAO fake now rejects pending-decision ownership, and conversation
  presentation regressions retain the chat mode/cloud badge through working, unread,
  read and home/server states, then verify an actual conversation-type transition.
  Full Android test execution remains the authority for these fixture corrections.
- CI production compilation and APK packaging passed for the integrated production
  source; the packaged chat/voice method-size gate, including Gateway recovery, also
  passed. Final release qualification still requires the final PR and main revisions.

- Diagnostic audit and Marketplace reuse the existing publisher-verified Robolectric
  cache before their unchanged, unconditional SHA-512 provisioner. This addresses
  repeated Maven 429 failures observed before compilation, while preserving every
  pin, deadline, Retry-After rule and downstream test. The cache-order/provisioning
  regression suite passed all 27 tests; workflow policy also passed.

## Concurrent voice integration

The shared branch advanced through `8b8a5ed` and `2dfb904` while PR validation was
running. Their caption, audio-clock, measured-backend, QNN graph and execution-phase
changes are retained. The independently pushed diagnostic formatting correction
was byte-identical to the local correction; the worker's commit was preserved by
fast-forwarding instead of publishing a duplicate. The original fifteen repairs,
Room 52 schema, release versions and all required checks remain intact.

Integration review found two additional recovery defects. Voice-only backend and
fallback reports now request extended readiness before requiring
`VOICE_EXECUTION_EVIDENCE_V1`. A failed or cancelled accelerator-to-CPU replacement
also marks its retired resident engine unavailable for cache reuse, so the next
playback owner rebuilds it; healthy cancelled engines retain their normal reuse
path. Warm-at-request and engine-reuse observations use the same availability
decision. Neither repair authorizes replay of already presented audio.

The combined source passed the 605-test host validation and 198 authentication
tests. Forty-four focused actual-source Kotlin checks include the newly retained
top-level diagnostic failure provenance. Ten focused failover/preparation JUnit
tests passed against the actual engine/evidence/backend sources and extracted
interface declarations. Two additional Robolectric regressions exercise the real
output cache and subsequent utterance after failed or cancelled replacement;
their compilation and execution remain part of Android CI. Three native partition/profile/ABI tests
passed with AddressSanitizer and UndefinedBehaviorSanitizer, including 3,000
randomized DAG trials. LeakSanitizer could not run under the workspace tracing
environment, and no leak-test pass is claimed. These component results do not
replace current-head Android tests, instrumented rendering, packaged DEX checks
or exact-main publication verification. Physical GPU/NPU and target-phone
audio/frame/memory performance remain unqualified as disclosed in the release.

## Full-suite corrections and shared-branch integration

PR validation run `45052936588`, job `137305365276`, executed 12,256 Android
unit tests on `f225c20`: 12,222 passed, 29 failed and five were skipped. Those
failures are retained as failure evidence, not counted as a passing qualification.
The following corrections address the observed failures without raising budgets,
removing assertions, or weakening the required workflow aggregate:

- GitHub checkpoint classification now snapshots at most sixteen distinct cause
  objects once. Cycles stop by object identity, and policy/persistence checks share
  that bounded snapshot. Compact tool descriptions preserve all schema structure
  and validators while reducing the ordinary schema estimate from 1,677 to 1,508
  tokens. Irreducible invocation tests retain their receipt/no-replay assertions and
  expect the existing explicit `BLOCKED` terminal outcome.
- Gateway client fixtures reset process-wide reachability evidence between tests.
  Explicit status refresh now clears the selected service's transport cooldown;
  passive cache invalidation, other ports, and network-generation boundaries retain
  their restrictions. Cancellation remains separate from a transport failure.
- Stream failure tests distinguish the closed application classification from the
  real HTTP status and original upstream semantic code. Authentication failures
  retain the actionable Settings → Platforms guidance. Typed local recovery
  reports once again retain their ownership fence.
- Migration tests verify the current schema, retained data, the diagnostic paging
  index, and the unclaimed privacy state of legacy conversations. Log grouping
  asserts process/session-scoped incident identities. Staged export fixtures match
  the capture's owned timing collector, preserving cancellation, resume, redaction,
  report correlation and chunk-integrity checks. Markdown tests verify effective
  laid-out character colours, including plain text's inherited TextStyle.

Concurrent commits `5e4ab1e` (setup, routing, benchmark accounting and voice export)
and `eeb2a20` (local privacy receipts, highlights and conversation badges) remain
in the shared history. The latter advances Room to 53. Concurrent commit `2fa6f59`
adds an early audit schema gate and artifact; its equivalent local draft change was
discarded in favour of the worker's committed implementation. The mandatory PR
schema-drift gate is unchanged.

Room 53 was exported by real KSP on `2fa6f59` in audit run `45058161842`, job
`137318034810` (generation succeeded at `2026-11-22T19:02:51Z`). Artifact
`14768098633` retains the 164,660-byte export; its schema SHA-256 is
`4d9294e56972dd23bb99841b263e026d9f4d97faa0acf71d4d40da4f9b28afdb` and Room
identity is `489fe6ad9e5f86a5af203af446e77dbf`. Those exact bytes and regenerated
agent build facts are imported; later CI must confirm there is no schema drift.

Focused host verification passes eleven log-grouping tests using the real grouping,
correlation and log-entry source, with explicit Android boundaries. Provider helper
checks reproduce two production defects before the repairs and pass all four after.
The GitHub schema harness verifies all fourteen operation schemas are structurally
unchanged after excluding description text; bounded-cause checks cover self-cycles,
two-object cycles and a forty-object chain. A real-Ktor host harness reproduces all
eleven Gateway failures on `5e4ab1e`, then passes all 38 current admission/dashboard
tests with the repairs; Android logging and injection remain explicit boundaries.
These focused results do not replace
the next complete Android, emulator, packaged-artifact and exact-main release runs.

Privacy integration review also found that an older UI snapshot could erase the
conversation's Privacy Mode marker or restore protection ranges cleared by a later
unmasked send. Snapshot persistence now retains the current marker and uses saved,
matching body/owner/revision evidence instead of incoming display claims. Edited
bodies and unknown revisions cannot borrow old protection. Cross-conversation or
role-changing message IDs fail within the transaction. Four Room regressions
cover marker isolation, cleared/narrowed ranges, edited/unowned revisions and
rollback. Nine exact-helper/receipt host scenarios passed; real transactions remain
subject to device execution.

Device acceptance now executes the Room 52→53 migration and all twenty persistence
cases, including those four regressions, on both configured emulators. It also
requires explicit successful receipts for all nine caption cases. Previously,
these database classes were only compiled and three caption methods lacked named
receipt requirements. All existing failure/skip checks remain; 67 host tests
validate the updated shell runner, missing/skipped receipt rejection and source
declaration matching. The authentic Room 53 export and matching build facts were
then committed concurrently as `7c4b142`; all three files were byte-identical to
the verified local import, so the worker's commit was retained without duplication.

The integrated working tree passes canonical `bash scripts/validate-local.sh fast`
with 608 host tests, generated metadata/inventories, dependency and secret checks,
Android resource/regex checks and whitespace validation. All nineteen changed
Kotlin files pass ktlint 1.3.1. The proposed 379-file release delta remains within
its explicit scoped manifest. Full Android and both device lanes remain pending
for the pushed revision; no earlier head's pass is used as final merge evidence.
