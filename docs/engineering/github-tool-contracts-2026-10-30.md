# GitHub tool contract repairs

## GitHub chat diagnostic follow-up

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base branch: `main` at `020b351bc3b98bd30cbdbb3443a332e8f5bd88f9`.
Working branch: `fix/github-tool-contracts-2026-10-30`, PR #765.
Repair checkpoint: `9144dee0ef386b0f9313e3fd0651143e284d3914`.
Intent: repair the uploaded build-132 diagnostics: cross-message source handles,
saved-page argument loops, repeated unsuccessful GitHub rounds, empty/protocol
finalization and the oversized repository-search coroutine. Retain task-scoped
mutation receipts and account/connection/conversation isolation.

The log contains 36 source-handle failures, saved-view correction loops, three
rejected provider tool frames and five oversized-search compiler warnings. These
are distinct from inaccessible CI resource IDs and unreachable external MCP hosts.
The latter cannot be made reachable by changing Android source.

- Retain at most 128 immutable source descriptors per repository and conversation.
  A new turn or process can inventory and reopen them without selecting an old
  branch revision. Descriptor aliases exclude source bodies. Cross-account,
  connection, conversation, repository and conflicting-commit reads are rejected.
- Keep captured result pages available across turns in the same credential,
  connection and conversation. Page delivery never dispatches a backend operation;
  mutation receipts and stages keep their existing task-specific authorization.
- Accept matching owner/repository/commit/action defaults when reading a saved
  page. Conflicting context still returns an exact cursor-only correction.
- Finalize after three consecutive unsuccessful GitHub tool rounds, resetting the
  bound on a successful result, including a saved read. Repeated empty responses
  and failed protocol repair receive one text-only finalization from saved results.
  The existing single-pass and cancellation policies remain enforced.
- Preserve final-response instructions inside the structured GitHub envelope so
  replay compaction retains both the instructions and executable recovery handles.
- Split blob prefetch and file matching out of the repository search coroutine.
  The compiled search method is 10,302 JVM bytecode bytes; Android ART behavior
  still requires an APK/device check.

Current follow-up validation: 91 Kotlin host regressions passed against the real
runner, facade, retained views, session and replay code. Android storage adapters,
telemetry, unrelated Combined types and UI answer projection are host stand-ins;
this is not a full Android build. All 238 canonical host tests, changed-source
ktlint 1.3.1, architecture-index regeneration and whitespace checks pass. The
targeted Gradle invocation failed downloading Gradle with `Network is unreachable`;
this workspace also lacks JDK 21 and Android SDK 37. Earlier hosted successes
below apply to their recorded head only; new-head Android verification is pending.

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base branch: `main`.
Base SHA: `020b351bc3b98bd30cbdbb3443a332e8f5bd88f9`.
Working branch: `fix/github-tool-contracts-2026-10-30`.
Intent: reproduce the distinct failures in the supplied deep-test reports, repair
argument/action contracts, CI reads and state handling, and publish a reviewable branch.

The reports repeat many entries and contradict one another about supported actions.
Their call counts, tool counts and sample IDs are not treated as verification evidence.
An empty artifact collection, a missing branch, a stale PR head, an active run and a
permission denial are distinct outcomes. No production deletion, cancellation,
workflow dispatch, review, staged patch commit or release is performed as a test.

## Checkpoint

- Created the remote branch at the observed main SHA; local checkout matches it.
- Confirmed missing facade aliases, direct-log redirect handling, failure-log
  expansion contract, and PR reviews without inline comments as repair targets.
- Implemented and tested action admission, CI routing, logs and mutation prechecks.
- Extended the same branch for the follow-up report: bounded GitHub pages receive
  separate result allowances; issue inspection/listing and paginated discovery are
  explicit capabilities.

## Repairs

| Area | Result |
|---|---|
| Action compatibility | CI `status` reads a run, job or selected commit; `rerun_failed_jobs` aliases failed-only retry, and `rerun_all` explicitly retries the full completed run. `commit_staged_patch` aliases staged commit; `ranges` is an optional read action. |
| Argument admission | Missing ranges/evidence, invalid paths/line spans, missing IDs, incomplete patches, and malformed staged commits fail before backend dispatch with the required fields. Workflow IDs accept discovery's integer IDs as well as filenames. |
| CI ID/state checks | A run ID passed as workflow ID is diagnosed through read-only discovery. Active reruns stop without cancellation; completed cancellations and successful failed-only reruns are observed no-ops. Acceptance receipts lead to read-only status checks. Permission denials remain permission denials. |
| CI collections | Jobs, workflows and artifacts honor `per_page`, retain IDs/filters in continuation calls, and report returned/empty counts. A missing collection is an invalid upstream response, not successful emptiness. |
| Logs | Direct logs share the redirect-aware, credential-fenced reader. Bounded line/character continuations preserve long Unicode lines. Failure digests expose executable log expansion, excerpt qualification, and resumable failed-job discovery beyond the ten-page inspection batch. The 4 MiB source-ingestion ceiling is explicit. |
| PR reviews | A review without inline comments uses the top-level review endpoint with the observed commit ID. Inline reviews retain their existing path. Stale heads stop before posting; PR creation validation points to branch/PR discovery. |
| Staged commits | The submitted expected head must match the saved stage. Replayed completed stages still return their retained receipt. A default-branch commit refusal explicitly identifies the need to create/select a working branch. Staging is a local preview; publication retains its existing working-branch and exact-approval checks. |
| Large parallel results | GitHub tasks use bounded allowances per result instead of exhausting a cumulative 24 KiB excerpt counter after a few saved pages. Calls, observations, authorization, retained-view capacity, timeouts and provider replay/context limits still apply. Page sizes respect the admitted output allowance, and every tenth progress checkpoint preserves the structured page instead of discarding fragments. This changes the app's own budget; it cannot change a separate host service's limits. |
| Issues/catalog | Issue requests admit `github_issue`; direct paginated listing excludes PR records without prematurely ending a mixed GitHub issues/PR page. Tool discovery inventories use twelve-record pages, preserve omission reasons and report operational status as untested. Repository issue counts do not replace issue inspection. |

## Validation

- Final canonical host validation: 234 tests passed, including CI policy, resource,
  regex, secret and whitespace checks.
- Kotlin 2.4.20 host compilation and JUnit execution use the repository's actual
  tool facade, native HTTP client/session code, budget implementation and Ktor
  MockEngine. Android storage, telemetry and unused Combined runtime dependencies
  are stubbed in this host harness; this is not a device or full-app build.
- Final Kotlin regression run: 185 tests passed, covering the original GitHub
  suites, the new HTTP/action/continuation regressions, parallel saved pages beyond
  24 KiB, call/zero-output ceilings, ordinary budget behavior and paginated discovery.
- ktlint 1.3.1 on every changed Kotlin file and the regenerated architecture index:
  passed.
- Targeted Android Gradle invocation was attempted but the Gradle distribution
  download failed with `Network is unreachable` in the Java runtime. This host
  also lacks JDK 21 and Android SDK 37. APK, Android lint and device execution
  must be checked by the branch CI; no success is inferred from the host subset.

## CI follow-up on PR #765

Repair base: `864ae21e776c36c59941f8513a7b4de2a40b9dad`; same working branch.
CodeQL run `41154624990`, job `124398586717`, failed before Kotlin compilation
while resolving `io.objectbox:io.objectbox.gradle.plugin:5.4.2`. Other checks had
already passed formatting and repository contracts; Android checks were running.

The ObjectBox marker and implementation were fetched from Maven Central. Their
SHA-256 values match the existing verification metadata: marker POM
`6847926c3bf9941bd1982cec5206da2493d57294f320f74bce076ece56b19047`,
implementation JAR `08f70685fb677ed83ea8d39a08a09afe274a412f9fcf273d1ab17b1b01ce2365`,
and module metadata `63b5deb386fd79abc9dc14ed7dac0333af6358590d548f989411181d375c5e33`.
The implementation POM also confirms version 5.4.2 and the official ObjectBox SCM.
Sources: [marker POM](https://repo.maven.apache.org/maven2/io/objectbox/io.objectbox.gradle.plugin/5.4.2/io.objectbox.gradle.plugin-5.4.2.pom),
[implementation metadata](https://repo.maven.apache.org/maven2/io/objectbox/objectbox-gradle-plugin/5.4.2/objectbox-gradle-plugin-5.4.2.module).

Plugin resolution now targets that already verified implementation directly.
CodeQL uses the existing bounded dependency retry runner and verbose resolution
diagnostics. Versions, trusted hashes, compiler/test failures and security gates
retain their existing enforcement; unknown-plugin errors without explicit
transient HTTP evidence are not retried.

Follow-up validation: all 235 canonical host tests pass; the retry process
regressions, actionlint and settings ktlint pass. A Gradle 9.8.0 smoke project
using the repository's plugin-resolution settings and unchanged strict
verification metadata resolves the ObjectBox implementation successfully, and
also configures a versionless consumer of the already loaded plugin. Its isolated
classpath includes AGP 9.4.1's published ASM 9.9 constraint so it matches the
existing application's verified dependency graph. No dependency version or
trusted checksum was changed. Full Android/CodeQL compilation remains a CI check.

CodeQL then passed on `e58de9230d7081ba5b00a1d0781829a444189745`
(run `41155543290`, job `124401425731`). The canonical contracts job separately
reported dependency-review HTTP 403. This security gate remains enforced.
Denied review requests now produce a `BLOCKED_API` artifact with a bounded,
credential-redacted upstream message and `scan_performed=false`; the independent
gateway/OAuth tests still run after a denied scan. No scan result is invented and
no repository security setting is changed implicitly.

## Replay delivery follow-up

Follow-up base: `e58de9230d7081ba5b00a1d0781829a444189745`, same branch.
Provider replay compaction could retain a next-page cursor after dropping records
from the current page, including small results from a large parallel batch. Each
retained result now supplies a scoped recovery call for its current offset.
Compaction offers that saved read before any later cursor, marks the preview as
unverified and delivers smaller recovery pages within the replay allowance.
Old call metadata no longer consumes the entire replay budget before the newest
result is admitted; complete old pairs are removed as necessary to maintain the
hard context bound. Capacity, expiry, scope and operation checks still apply.

Regressions reconstruct Unicode data without gaps through pressured replay,
recover mutation receipts with exactly one backend mutation, reject forged page
ceilings and foreign scopes, and verify that denied dependency scans still fail
and retain safe diagnostics.

Follow-up host validation: 197 Kotlin tests passed, including the full exchange
compactor suite and the new recovery regressions. All 238 canonical host tests,
changed-source ktlint, workflow actionlint and whitespace checks passed. Cloud
checks must be refreshed on the next published revision; the earlier CodeQL
success is evidence for the plugin repair, not approval of a later head.

## Expanded reliability and delivery scope

Expansion base: `9144dee0ef386b0f9313e3fd0651143e284d3914`; the same PR #765
and working branch remain in use. The owner requested the reliability, recovery,
CI, visual validation, release and observability additions while a merge was
waiting for checks. Merge authorization remains pending completion and validation
of this expanded scope; earlier green checks do not qualify the new revision.

Inspection found existing encrypted repository snapshots, a Room engineering
ledger with compare-and-set Git saga checkpoints, WorkManager check watches,
read-only HTTP retries, device/visual fixtures, release identity/provenance gates,
artifact retention and opt-in signed CI notifications. The three `apply-*-ui.yml`
files named in the proposal are absent from the current source tree; historical
registered workflow IDs are not current workflow definitions.

Confirmed gap: staging the same patch writes the same deterministic stage ID but
replaces its record, potentially removing a saved commit/review receipt. The
expanded implementation must preserve immutable versioned stage records, serialize
publication claims, and reconcile uncertain outcomes without replaying writes.

Delivery checklist:

- Deterministic execution identities, retained lifecycle/checkpoint context,
  correlation and concurrent invocation recovery.
- Versioned immutable patch snapshots, atomic publication claims and safe restore.
- Bounded transient retries and circuit admission with behavior regressions.
- One configurable UI pipeline and shared build implementation with existing
  signing, memory, provenance and native verification requirements preserved.
- GenieX stage metrics, retained diagnostics and selectable runtime validation.
- Visual baseline comparison, emulator matrix and workflow smoke contracts.
- Release preparation/changelog/indexing and non-destructive rollback planning.
- Measured workflow reliability/latency objectives, trends, capacity and dashboard
  evidence; optional notification destinations require owner configuration.

Numeric speed/flakiness targets in the proposal are objectives, not measurements.
Physical-device qualification, credentials, signed production publication and
external notification delivery require their existing concrete runtime evidence.

The subsequent coding-accuracy list is part of this same expansion: dependency-aware
multi-file context, commit/branch history, versioned execution/output identities,
preserved error evidence, pre-commit feedback/test planning, fuzzy/pattern/reference
discovery, revision-bound cache reuse, semantic patch context, DAG recovery,
inline editing/highlighted diffs/contextual help, and retained repository guidance
for tests, generation and refactoring. The proposed accuracy percentages are
unmeasured objectives. Commit IDs, workflow/run IDs, evidence handles and opaque
saved-view cursors remain distinct typed identities; examples in the proposal do
not override the admitted tool schemas or publication safeguards.

## Integrated delivery checkpoint

Before publication, the PR head had independently advanced to
`856115333ee3464c3d996b2109cdebd8a786c2a0` (chat continuity and bounded completion).
The expansion was rebased onto that head. Both cross-turn source descriptors and
multi-file dependency metadata are retained, and both sets of evidence/recovery
regressions run together. No remote branch history was overwritten.

The [delivery matrix](github-reliability-delivery.md) maps every recommendation
group to its implemented capability, existing subsystem, and concrete limits.
The shared UI pipeline and release preparation are manual entry points. No
notification destination, physical-device qualification, approved design baseline,
or production release is fabricated or enabled by adding these definitions.

Integrated local validation passed: 236 Kotlin host regressions against the actual
runner, facades, native HTTP/session, saved views, execution journal, circuit,
multi-file context, patch validation and replay code; all 248 canonical host tests;
changed-source ktlint 1.3.1; actionlint; architecture index and whitespace checks.
The host harness retains its stated Android storage/telemetry stand-ins and does
not compile Compose or run a device. Full Android, emulator, native APK and CodeQL
checks must qualify the newly published head before merge. The earlier CI results
are not approval of this expansion.

## Hosted full-suite follow-up

On published expansion `7b140d21c6f27567c78c4ebe43b6bda1858a835c`, CodeQL,
Android lint, dependency review, formatting, contracts, and build-download checks
passed. The full unit suite ran 5,541 tests and found two failures (four skipped):
an old runner assertion expected a reused successful read to be an error, and a
release test requested two dispatches without distinct execution identities.

The runner regression now verifies successful receipt reuse, no repeat dispatch,
and continued availability of another read. Release publication retains its
complete original receipt/correlation under a canonical execution claim; a separate
explicit request supplies a new `execution_id`. Exact accepted run/source/tag
associations persist across client restarts and conversation turns. Missing or
foreign receipts remain unknown, and a changed expected source stops dispatch.
Release schemas admit the execution, ref/inputs and exact run selectors needed
for publication and subsequent verification. Lost acknowledgements are tested
without repeating the publication.

The expanded host harness additionally exercises the runner replay/completion
tests and native release receipt tests. Android tool-resolver, legacy MCP adapter,
and provider error-classifier integrations remain outside this host subset; the
hosted Android suite runs those actual adapters. A new publication must receive
fresh hosted verification before merge.

The expanded API 34 phone run also failed two compositor assertions while API 36
tablet coverage passed. Actual retained PNGs in artifact `13028614063` show a
Pixel Launcher ANR dialog covering both sampled regions; the app's stripes and
green parent are visible underneath. This is not evidence of an app blur failure.
Emulator preparation now disables only a resolved, allowlisted stock launcher
before these app-owned Activity fixtures. It never changes the app under test or
suppresses system error dialogs. Unknown launchers and command failures stop
preparation. Pixel tests retain their screenshot and assert that the qualified
app owns the foreground window before judging colors. Both API/device profiles
remain in the matrix; fresh runs must verify the correction.

Follow-up local verification: 285 Kotlin host regressions and all 251 canonical
host tests passed. Changed Kotlin formatting, workflow syntax, architecture index,
secret scan and whitespace checks passed. The full Android/device suite remains
the qualification boundary for the next published revision.

## Publication fences under cache pressure

All applicable hosted checks passed on `d803f0057e6cf9b5acab1e53fc6fbba69eb32753`,
including Required verification, CodeQL, both emulator profiles, diagnostic audit,
review APK and complete GenieX minified cold packaging/native audits. Main Android
unit results were 5,544 run, 5,540 passed, four skipped, zero failures.

A final durability regression then reproduced a separate failure: after an
uncertain write, ordinary cache pressure evicted its execution record, allowing
the same invocation to dispatch again. Execution identity fences now have separate
retention from cached receipt bodies. Receipt eviction/removal, memory pressure,
and restart return `RECONCILE_REQUIRED` instead of admitting another write.
Empty persistent fence files contain only hashed identity in their names; source
and credentials are absent. A bounded fence-capacity limit fails closed for new
claims, preserving existing receipts and read-only work. Regression tests exercise
actual memory eviction, disk-quota pruning, restart, receipt removal and admission
at capacity. The new storage revision requires fresh hosted checks before merge.

Final local verification of the storage revision: 289 Kotlin host regressions,
251 canonical host tests, changed-source ktlint, architecture index, secret and
whitespace checks passed. The earlier all-green hosted result is retained as
evidence for `d803f00`; it does not qualify the new storage head.

## Emulator boot readiness

The API 34 run on storage head `7a42859ec48205745a33ce50070a4e57b2da1a9e`
stopped before app tests: the first post-boot home resolution was not an allowlisted
launcher. Preflight now observes home readiness read-only for at most 60 seconds,
including subprocess timeouts, before changing any package. Only a resolved stock
launcher can be disabled. Offline boot discovery can retry; permission failures,
malformed components and mutation failures are not replayed. A stable unknown
home times out with its component in retained diagnostics, rather than silently
passing or changing the app under test. Failed setup also writes a `BLOCKED`
artifact. Deterministic clock tests cover missing/fallback home transitions,
offline transport, unknown components, hard budgets and permission failures.

Android's [upstream FallbackHome](https://github.com/aosp-mirror/platform_packages_apps_settings/blob/android-14.0.0_r1/src/com/android/settings/FallbackHome.java)
waits for user unlock and a real home after boot; `sys.boot_completed` alone does
not establish that launcher resolution is ready. The setup keeps that transition
separate from application pixel qualification and preserves both device profiles.

The readiness revision passes all 257 canonical host tests, including nine
emulator preparation regressions. Kotlin source is unchanged from the storage
revision's 289-test host pass. Fresh hosted device and merge checks remain
required after publication.

## Keep an actual emulator home selected

Acceptance run `41175909700` on `6d185b26` passed API 34, but API 36 retained
a System UI ANR over both glass fixtures and four early instrumentation startup
ANRs. Its log contains repeated Settings.FallbackHome "no home" messages after
launcher disablement and about 92% CPU pressure during those startup failures.
This is additional emulator evidence, not a successful visual qualification or
proof of an application rendering defect.

The replacement setup installs both APKs first and selects a framework-only
`EmulatorHomeActivity` packaged exclusively in the test APK. It verifies the
installed component, Android's home-selection acknowledgement, the resolved
default and the actual launched activity before stopping the observed stock
launcher. Stock packages are no longer disabled. Density and font changes occur
only after a real home is active. Permission failures, unknown components,
missing fixtures, unsuccessful launches and uncertain setup commands stop the
run; neither application ANRs nor foreground/pixel assertions are suppressed.

The shell selection follows Android's
[set-home-activity implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java).
Fifteen behavior/fixture regressions cover selection, launch verification,
Android's metadata-prefixed brief output, resume, bounded discovery and failures
without mutation replay. Fresh API 34
and API 36 validation on the published revision remains required.

## Completion review and release preparation (October 31)

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Base: `main` at
`020b351bc3b98bd30cbdbb3443a332e8f5bd88f9`. Working branch:
`fix/github-tool-contracts-2026-10-30`. Review began at
`6d823d67f532afbf4d9119288b00ba0724d99f38` after fast-forwarding the two
Android acceptance setup repairs. Intent: complete the contract, recovery and
release follow-up, qualify the final PR revision, and publish a forward scoped
release only after its exact-commit gates pass. The application engineering ledger
is device-owned and unavailable here; this repository ledger preserves the review
checkpoint without claiming that device work or external receipts were reconciled.

- Local tool invocation contracts now declare a major/minor version. A new major
  requires an adapter; an additive minor remains compatible. Direct and budgeted
  tools reject unsupported majors before dispatch. Discovery records this version
  separately from schema fingerprints and invalidates observations when it changes.
- Tool exchanges require unique matching call/result identities before provider
  replay. Direct, delegated and provider-owned receipt normalization preserves
  unknown outcomes. Direct mutation exceptions/timeouts remain unknown and block
  an identical new invocation in that run; known read failures remain distinct.
- Checkpoint loading honors Android AtomicFile backup recovery and rejects corrupt
  identities. Snapshots report completed, failed, uncertain, queued and rejected
  counts under the same mutex. Concurrent-instance and backup-only regressions
  verify that restored writes are not sent again. These counts describe the local
  journal, not the unobserved 34 operations mentioned in the original report.
- Tool health distinguishes unknown outcomes from failures, reports run-local
  counters and observation age, and marks observations older than five minutes
  stale. It does not perform unsolicited external probes or infer live availability.
- Forward rollback proposals bind to exact observed ancestry. Merge reversions
  require an explicit parent; stale/unrelated/root targets fail. Tests apply the
  proposed command to real disposable Git histories and verify that later changes
  survive. No tag or published artifact is rewritten.
- Release preparation advances to 0.9.65.0/build 133 with an exact changed-file
  scope and carried-forward limitations. Publication uses the existing signed
  workflow and must still pass Android, security, native, signer-continuity and
  exact-source checks. Pending jobs are not successful evidence.

Local completion validation: 321 focused Kotlin/JUnit regressions passed using
the existing host harness (Android integration substitutes, including AtomicFile;
three Android/resolver integration tests excluded), 268 canonical fast checks
passed, changed Kotlin passed ktlint 1.3.1, architecture index and whitespace
checks passed, and the complete working-tree release scope validated. The new
backup test is also part of the real Robolectric suite in hosted CI. The original
queued-work fixture now uses a different target from the uncertain write, because
an identical operation must remain blocked even under a new call ID.

Final consistency review: restored legacy receipts carrying an unknown-outcome
code are normalized as errors, matching fresh receipts. Authorization categories
include revocation and unauthorized codes; an unclassified tool failure no longer
invents a provider outage. Both cases have focused behavior regressions.

Exact-head hosted validation on `e6fccf9b4eb3ed02d7e5b0cb7f05ec51866f7d86`
passed Required verification, Kotlin formatting, CodeQL, diagnostic audit, review
APK, real-JNI/native memory smoke, and API 34/API 36 acceptance. GenieX run
`41180689148` passed compilation, isolated tests, debug assembly and R8, then
produced no output after AAPT2 shutdown in `packageNativeSmoke` and exhausted the
full 90-minute packaging bound. Its retained log contains no OOM or build error.
The follow-up keeps the 4 GiB heap and one Gradle worker while raising only
ApkFlinger's separately bounded common pool from one to two workers. A one-shot
15-minute watchdog captures process, heap and thread evidence for another slow
attempt without changing the timeout or audit requirements. Thirteen delivery
tests and all 269 canonical fast checks pass; fresh hosted validation is required.
