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

Production compilation of the concurrent research UI change exposed one missed
call site: a trailing engine-selection lambda bound to the newly added optional
help callback. `DeepResearchSettingsCard` now names `onEngineEnabled` explicitly;
its enabled guard and selected-engine update are preserved. Both panel call sites
were inspected, and the corrected file passes ktlint. Full Android compilation
remains required on the next pushed revision.

## Final concurrent reporting and caption integration

Concurrent commits `9e4b767`, `5013113` and `3e58871` are retained. They add safe
unknown-provider-code correlation, stronger session/cooldown regressions,
continuous caption leading, source-bound privacy display preparation, and the
hosted contract's numeric `401`/`429` parity. The privacy projection retains only
unchanged source runs after hidden reasoning is removed; source-list transforms
without an offset map conservatively withhold highlighting. Prepared text and
protection evidence share one snapshot, and current evidence can revoke it.

Integration review found that an unknown upstream code's safe hash stopped at
the local failure record. Compact exports and approved reports now retain it
through the existing `original_error_code_hash` field. A known upstream code
takes precedence over an inherited hash; otherwise a strictly typed SHA-256
string is retained, with the canonical application code as the report fallback.
An upstream error type remains separate from the upstream code, including when
the type supplies the application classification. No public field set is expanded,
and arbitrary upstream code text is never added to the approved report.

Further caption stress testing reproduced forward-retarget overshoot: motion
from line 0 toward line 8, retargeted to line 9 at 250 ms, reached approximately
9.095 before reversing. The trajectory now retains its sampled position and
velocity while shortening a forward segment when necessary to remain monotonic.
Pause/resume, reverse-target braking, manual seeking and reduced-motion behavior
retain their ownership and timing rules. These focused corrections remain subject
to the complete current-revision Android and device checks before merge.

The final focused motion suite reproduces four failures before the correction and
passes all 25 cases afterward, with no skipped tests. It covers skipped lines in
both directions, ninety close-target/late-callback combinations, subnanosecond
duration precision, paused retargeting and reverse-target velocity continuity.
Six committed provider classification tests and three actual-source compact
projection probes pass. The full authentication/reporting suite passes all 200
tests, including approved numeric-code reports and unknown-code hash round trips.
The 39 actual-source Ktor admission/dashboard tests also pass. Stored-message and
public-capture integration regressions are committed for the full Android suite;
the focused host checks do not substitute for those results.

The combined follow-up passes the canonical fast validation with 608 host tests,
all nine changed Kotlin files pass ktlint 1.3.1, and the proposed 387-file release
delta passes the existing exact-path scope validator. Android 0.9.66.13/build 147,
Gateway 18.1.1, the eight required main workflows and every disclosed qualification
limit remain unchanged. Final Android/device checks and publication verification
are still pending for the pushed revision.

## Device-discovered built-in binding constraint

Acceptance run `45059368354` on `6e03f1e` executed the newly required persistence
class on both API 34 and API 36. Both found the same failure in
`builtInToolBinding_rejectsDuplicateProfileAndTool`; the other nineteen persistence
methods and the privacy migration passed. The three-column unique index does not
prevent duplicates when the connection key is NULL, which is how built-in tools
are represented. This is a storage defect, not an allowed test skip.

The repair installs INSERT/UPDATE guards at database open and normalizes only
equivalent legacy built-in bindings, keeping the greatest `(created_at,
binding_uid)` deterministically. Cleanup and guard installation are atomic. The
DAO preserves its replacement behavior by replacing a built-in profile/tool key
inside the existing transaction before its raw insert. Connected tools retain
their existing uniqueness and foreign-key semantics; tables, indices and the
Room 53 export remain unchanged.

The normal database and temporary backup databases use the same callback. A
legacy backup's staged source is normalized after migration and before validation
and restore, within its caller-owned transaction. The original archive is
preserved. This prevents the new target guards from rejecting an otherwise valid
older archive containing redundant built-in rows.

Independent execution of the actual helper SQL against the exported Room 53
table/index definitions passes seventeen behavioral checks: insert/update guards,
same-primary-key replacement, self-renaming, deterministic legacy cleanup,
separate remote connections, failed-replacement rollback, caller-transaction
rollback, and unchanged column/index/foreign-key metadata. Real Room callback,
backup restore and emulator execution remain required on the repaired revision.
The strict twenty-method persistence receipt gate is retained.

The repaired Room method also asserts that a deliberately rejected replacement
rolls back to the complete previous binding list. The backup regression restores
a real archive containing legacy duplicates and verifies unchanged archive bytes,
retained remote connections, the deterministic survivor and the live constraint.
All five DAO fakes inherit the transactional replacement wrapper. The integrated
follow-up passes 608 canonical host checks, all thirteen changed Kotlin files pass
ktlint, and the proposed 398-file delta passes the exact-path release scope. These
local results leave the new Room and backup cases pending full Android execution.

## Full-suite confirmation and concurrent feature wiring

The completed Android unit job `137323925528` from PR validation run
`45059996467` on `5013113` reports 12,497 tests: 12,490 passed, two failed,
five skipped and no errors. Exact class/method comparison with the original
29 failures confirms that all 29 now pass. The downloaded 1,837,555-byte JUnit
artifact has SHA-256
`03129117559d3f0755b618fa92b0e0e3a5c554039c276d95f781949481b3294d`.
This is evidence for that historical revision; the newer storage, hash-projection
and feature changes still require the complete current-revision gates.

The two remaining failures have narrow corrections. The issued-cursor description
now explicitly tells callers to copy the exact issued cursor as the only field.
The actual admission test passes across all fourteen full/projected/inspection
variants; all typed schemas remain unchanged apart from descriptions, and the
ordinary/conservative native catalogs remain within their existing 1,600/2,000
budgets at 1,486/1,914. The Gateway status fixture now stubs the nullable refresh
argument and verifies that the selected Gateway configuration is passed exactly
once before checking request cancellation and cleared measurements. An actual
MockK 1.14.11 check covers both null and non-null arguments; full ViewModel
execution remains part of Android CI.

Concurrent `f3b9b40` is preserved. Independent reviews cover measured voice-engine
selection and explicit recheck, corrected recognition ranges and supplementary
RTL shaping, per-decision draft state and compact choices, Research controls when
history is unavailable, profile route propagation, benchmark disclosure, and
observed-absent versus unavailable firewall evidence. No additional concrete
defect was found in those reviews. Six actual-source caption layout tests and
all ten collector tests pass; the collector tests include real PowerShell 7.4.6
execution with no skips. All 453 Gateway package hashes and the generated file
inventory match. New Compose/ViewModel cases, all thirty required device receipts
per API, and complete Android execution remain required on the combined revision.

Android 0.9.66.13/build 147, Gateway 18.1.1, Room 53, the eight required main
workflows, public reporting contracts and hardware qualification limitations
remain unchanged. Publication is authorized only after current PR checks, normal
protected merge, exact-main checks and both release verification paths succeed.

The combined correction passes all 608 canonical host checks, all three changed
Kotlin files pass ktlint 1.3.1, and the complete 405-file proposed release delta
passes the unchanged exact-path scope validator. Generated architecture,
diagnostic schema, app inventory and Room-derived metadata checks also pass.

## Packaged native verification and Research editor state

Concurrent `459261b` and `e9148ab` are retained. The APK check now requires both
Kokoro timing and adapter libraries for each packaged ABI, verifies ELF identity,
and checks the adapter's direct dependencies against same-ABI packaged libraries
or the existing Android system-library list. All seventeen focused native
inventory cases pass. This validates packaging rules, not physical accelerator
execution. An eight-way Room replacement regression is now required on both
acceptance APIs, bringing the explicit gate to 21 persistence, one migration and
nine caption receipts per API. The selected-Gateway refresh fixture also checks
the null-selection path separately.

Research presets, report options and feature controls now follow the editor's
read-only and saving state. Save captures both draft groups together before
suspending, and edits are rejected during the save. All shared tab call sites
retain their trailing selection callbacks. Review found one older instrumented
assertion still expecting retired effort/cap wording; it now checks the visible
`500 source goal · 100% depth` text. The 500-source custom goal, disabled editor,
320 dp width and enlarged-text fixture are unchanged.

The integrated Research revision passes 615 canonical host checks, all six
changed Kotlin files pass ktlint, and its 410-file release delta passes the
existing exact-path scope. The assertion follow-up adds only its explicit test
path. Full current-head Android, 31 focused receipts per API, native APK byte
checks and both exact-main publication verifications remain required.

The assertion follow-up passes the same 615 canonical checks, its changed Kotlin
file passes ktlint, and the proposed 411-file delta passes the scope validator.
