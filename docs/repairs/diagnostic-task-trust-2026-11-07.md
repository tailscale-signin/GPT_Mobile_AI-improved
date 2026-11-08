# Diagnostic task trust and recovery — November 7, 2026

## Accepted scope and source identity

This change implements the supplied F01–F23 diagnostic review on
`fix/diagnostic-remediation-8597579-2026-11-07` in
`tailscale-signin/GPT_Mobile_AI-improved`, preserving the existing PR 813 work.
The inspected base is `main` at `3ac89ceafd9083f67da30e92f9f93aae27be4600`.
Work began at `156ca471af822bc6a0619fb7395ab869d151a535`; the concurrent
`423b917196419605a6c789810d3d469c55d09ce8` SQL-fixture typing repair was inspected
and incorporated before publication. Its fixture correction matched the local
correction exactly. The published commit and current CI checks are authoritative
in [PR 813](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/813).

The companion task checkpoint records the accepted scope, validation boundaries,
and remaining external verification. This document describes source behavior;
it does not substitute host tests for Android CI or physical-device measurements.

## Completion and recovery model

Standard chat and dedicated GitHub chat establish the same native repository
acceptance boundary. An operational GitHub request requires one authorized
repository/connection; a missing or ambiguous target fails before repository
dispatch. A task contract retains the original user-message identity, repository
and ref, requested actions, acceptance criteria, required tools, original call
limit, explicit deadline, receipt-store identity, and cleanup policy.

Short follow-ups resolve to that accepted task. A bare integration retest inherits
the accepted restrictions. Expanded requests with changed scope, budgets, or
selectors establish a new authoritative task; they cannot be replaced by an
earlier write-capable request. Full-request comparison preserves case-sensitive
branch and path identities. A session regression confirms that a new read-only
request cannot schedule the earlier task's fixture branch. Follow-ups retain the spent call budget
and deadline, including retry aliases and primary-only controls. Temporary chats
still enforce the current run's accepted contract without creating persistent
workspace records. Historical time windows in a prompt are not interpreted as
execution deadlines.

Invocation completion, readable answer delivery, and verified task completion
remain independent. A plan without operations cannot complete an operational
request. It receives at most one tool-enabled corrective attempt; text alone from
that attempt cannot bypass the selected review pipeline or become evidence.
Unfinished work produces a receipt-derived partial or blocked report.

Execution recovery keeps tools and checks uncertain mutations before replay.
Failure-explanation requests use historical diagnostics. Requests to retry writing
or show the report deliver a binding-, receipt-set-, and content-hash-checked saved
answer without rerunning the task. The existing structured continuation and
paragraph-boundary behavior remains in place.

Pure repository continuations replace accumulated plans with the complete accepted
task, newest instruction, and a bounded receipt projection with exact observed
selectors and counts. Full receipts remain in the protected store. Research and
Combined flows retain their own delivery protocols. Corrective requests use the
same bounded projection. Omitted older evidence is explicitly identified; its
absence is not permission to invent selectors or repeat writes.

## Finding disposition

| Finding | Implemented or preserved behavior | Regression evidence |
| --- | --- | --- |
| F01 | Native task acceptance, target setup blockers, durable contract, and Standard/dedicated budget parity | `GitHubTaskAcceptanceScopeTest`, `GitHubTaskTrustTest`, task recovery tests |
| F02 | App-owned receipt verification separates invocation, answer, and task outcomes; proposed work cannot satisfy acceptance | Plan-without-calls and corrective-prose regressions; existing acceptance and execution-ledger tests |
| F03 | Execution resumes with authorized tools; writing and diagnostics are separate; uncertain effects retain reconciliation fences | Tool-preserving correction, writing-only saved-answer tests, existing output-continuation and mutation-recovery tests |
| F04 | Initial discovery omits invented versions; subsequent pages use exact cursors and opaque versions; one explicit restart after stale pagination | Catalog readiness and authorized-catalog regression cases; authority invalidation tests |
| F05 | Discovery totals, successful calls, page coverage, GitHub denominator, and invocation/verification coverage come from eligible receipts | Fabricated `87/87` and nine-page report regression; integration acceptance tests |
| F06 | Newest instruction is retained; a stalled plan receives one corrective execution attempt, then an actual incomplete report | `GitHubTaskTrustTest` corrective-context, no-progress, diagnostics, and saved-report cases |
| F07 | Explicit qualified aliases establish required readiness; pending, timeout, acquisition failure, absence, ambiguity, and authorization are typed separately | Delayed catalog, qualified alias, disabled/missing/ambiguous tool regressions |
| F08 | Persisted root request drives readiness and tool resolution across short controls and process-restored history | `TaskRecoveryActionTest`, task-continuity and alias regressions |
| F09 | Terminal recovery checks nested saved-job identity, preserves partial text and original failures, and provides a readable result without fabricating a job ID | Gateway result-recovery tests; transport integration cases |
| F10 | Existing fixture ownership, explicit branch binding, expected heads, verification, cleanup, and uncertain-write fences remain enforced | `GitHubOwnedFixturesTest`, `GitHubContentFixtureTest`, existing branch and mutation acceptance tests |
| F11 | Existing action-specific schema alternatives require observed issue identifiers and freshness guards; one corrected proposal has a new call ID | Actual operation-facade missing-issue/repeated-invalid/corrected-new-call regression |
| F12 | GraphQL application errors remain failures despite HTTP 200; safe type/path/resource/operation evidence is retained, with explicit truncation | `GitHubHeaderQueryTest` |
| F13 | Real policy changes retain field-level reasons and completed/uncertain receipts; local pagination staleness does not become authority loss | Policy-invalidation, failure-metadata, and durable failure-classification tests |
| F14 | Definition-page timestamp maxima are parsed and computed; page coverage is explicit; definitions cannot prove branch head or build status | `GitHubWorkflowEvidenceTest` |
| F15 | Typed readiness/failure stages survive serialization; original and resolved arguments are sanitized, bounded, and separately recorded | `GitHubArgumentProvenanceTest`, `ToolFailureMetadataTest`, `ProfileFailureRecordTest` |
| F16 | Definite unsupported `/props` and `/health` results remain cached for the relevant route/credential/network/instance/revision identity | 24-hour negative-cache, revision-change, and explicit-invalidation tests |
| F17 | Upstream events, actual ASGI handoff/send completion, Android parsing, and run-scoped Compose draw are separately observed | Delayed event/backpressure tests, malformed-optional-telemetry tests, draw-lineage tests |
| F18 | Measured frame-phase breakdown, refresh-rate/build identity, and balanced export traces support focused device investigation | Frame phase accounting and balanced-stage trace tests; device comparison remains unmeasured |
| F19 | Timeline reuses the captured journal; source/chunk hashes and counts are verified; serialization and hashing are bounded; resume refreshes partial derived stages | Frozen-source adapter regression, corrupt-source rollback, cancellation/resume, coverage, and 64 MiB heap gate |
| F20 | Immutable profile revision, configured/requested/serialized reasoning, backend-reported model, and endpoint digest remain distinct from unmeasured runtime facts | Reasoning receipt, provenance DTO, parser, transport, and invocation-ledger tests |
| F21 | Resumed and corrective repository prompts use the accepted task and bounded execution ledger instead of accumulated plans | Exact selector, constraint, latest-intent, bounded-count, and private-body exclusion tests |
| F22 | Composer input ownership is released before send, at externally started runs, and on disposal without clearing the draft or stealing another editor's focus | Eight Compose lifecycle regressions plus a real Android BLAST-factory lifecycle check; actual focus at dispatch, keyboard mode and traversal remain asserted |
| F23 | Routine omissions become compact changes; bounded inventory snapshots are exported; actual authority failures retain detailed warnings | Snapshot/diff/clear/privacy tests and staged-export adapter tests |

## CI integration follow-up

The concurrent `b34c7dc01368584a480a20a83d15f0f573a11f15` update is preserved,
including persisted check-watch identity, selected-revision staging, late tool
admission, and the one-time output allowance for a newly admitted planner. Its
test repairs use the same full verification snapshot contract as production;
compact audit events are not substituted for saved verdicts. The subsequent
`87709e6f5ff91befb4ede0f30fcbd1d48999afe7` update is also preserved: owned
content fixtures may start from a selected feature branch, while exact source
leases, same-task fixture ownership and guarded cleanup remain mandatory. The
legacy fallback stays restricted to the default branch.

The follow-up retains the existing schema and prompt-token budgets while restoring
explicit integer bounds. Source ranges reject unknown selectors, wrong JSON types,
nulls, and oversized offsets before backend dispatch. Path-only requests retain
their existing bounded default read. This prevents an unrepresentable offset from
silently becoming zero.

Branch regressions inspect the actual operation envelope, distinguish selected
reading context from an explicit review target, require a complete proposal and
exact lease, and prove that rejected requests cause no mutations. Missing observed
SHAs remain absent. A default-branch check may read repository metadata; the write
still fails before mutation. User-message fixtures explicitly supply the required
nullable `platformType`, matching the real Room entity constructor.

Known-profile OpenAI Responses, Anthropic, Gemini, and OpenAI-compatible requests
carry immutable profile provenance into the shared serialization boundary. The
invocation ledger records bounded per-attempt settings from the final admitted
payload, including a transport failure that never emits a usage event. Retries
preserve earlier settings, and overflow has an explicit omission count. Only
allowlisted reasoning controls are retained; prompt bodies, credentials, and
unacknowledged effective settings are excluded.

The complete Android unit run on `41cb3135a417183b8a6638d4fa447524872ed57e`
compiled all production and test sources, then executed 7,667 tests: 7,642 passed,
20 failed, and five were skipped. The failures were confined to three transport
test classes and two composer lifecycle cases. Android lint, both API 34 and API
36 emulator jobs, all four gateway matrix jobs, the Windows installer, CodeQL,
and the APK catalog/download contract job passed on that predecessor. Its review
APK and benchmark compilation also passed. All three packaged APK variants passed
runtime, privacy, native alignment and DEX checks; the largest checked method was
15,900 code units, below the unchanged 16,383 limit.

The transport follow-up removes synthetic request-receipt and header-only chunks
from the provider response stream. Actual success, recovered-result, error and
degraded-stream chunks retain their observed timing and admitted reasoning
receipt. The serialization-to-ledger bridge still records attempts that receive
no response. Both OpenAI transport entry points now apply connectivity admission
before gateway capability negotiation, so a known device outage cannot dispatch
an otherwise valid gateway probe.

Recovery fixtures use independent mock route identities. A verified gateway
instance deliberately survives network epochs in production; sharing one mock
host across unrelated cases previously caused later direct-provider tests to
perform gateway discovery. Exact retry counts, zero-dispatch assertions,
partial-output retention and mutation-replay fences remain unchanged, and the
outage regression now covers already observed gateways as well as direct routes.

Composer release moves focus to its non-editable container before dispatch or a
run-start transition. Clearing the Android focus owner in keyboard mode could
immediately restore focus to the first editor; forcing a false reporting callback
then concealed that real ownership. Actual editor focus events now drive the
reported state. Disposal still releases the departing editor, and an unfocused
composer does not dismiss another editor. The regressions retain all original
draft and refocus assertions, inspect actual semantics during dispatch, and cover
Ctrl+Enter and another editor's focus during run start.

All 47 tests in the three affected transport classes pass in a focused JVM
harness using 159 current production source files, three repository test files,
36 explicit host boundary or extracted-source files, and reused catalog support
classes. The compiled source hashes match the working tree. The six composer
cases require actual Android/Compose CI; the host transport result does not
qualify them. Canonical fast validation again passes 398 tests, and all six
changed/generated Kotlin files pass formatting.

## Composer graphics fixture follow-up

The concurrent `515d9b064eda674feaea1f411b1ee8d4af4a7d0d` update is preserved.
It makes the non-editable focus destination temporarily eligible, so it does not
add an ordinary keyboard traversal stop, and strengthens transport and input
ownership assertions. Its full Android suite executed 7,672 tests: 7,664 passed,
three failed, and five were skipped. Independent canonical and diagnostic results
agree. All 47 transport/rejection/recovery tests and all 16 gateway-negotiation
tests pass in the real Android build. Both emulator variants pass 56 tests in 20
instrumentation groups; lint, CodeQL, all gateway jobs, installer, catalog
contracts, review/benchmark builds and packaged runtime/privacy/DEX checks pass.

The three remaining failures occur during composer Activity teardown, with a
null magnifier surface. Pinned Android 14 and Robolectric 4.17 sources establish
the cause: the magnifier unconditionally destroys its BLAST surface, but the
legacy test setup returns no surface. At API 34, Robolectric requires both native
graphics and real draw traversals to provide that surface.

That checkpoint enabled native graphics and wrapped the Compose rule in
an outer `RuleChain` resource. It enabled `robolectric.useRealDrawTraversals`
before Activity creation, retained it through Activity/Compose teardown, and
restored the exact previous property value or absence afterward. Only the combined
chain was registered as a rule. All eight test bodies and their synchronous
actual-focus assertion helper remained unchanged; production focus behavior was
unchanged. The pinned JUnit 4.13.2 and Compose 1.12.1 rule contracts confirmed the
cleanup order. Subsequent actual execution exposed the native crash described
below, so this experiment was superseded rather than treated as a passing repair.

The concurrent `54faf9a197e1ee897901e56106802d920a5fd41e` MCP/audit update is
also preserved in full. It adds bounded OAuth recovery, exact-configuration
connection repair, typed local/protocol failures and a qualification worklist,
and narrows conversational diagnostic intent without replacing repository work.
It has no overlapping path with the composer fixture and this ledger update.
The final fixture correction is applied on top of that combined source; the
complete resulting head requires fresh Android CI.

## Legacy surface fixture and native-crash evidence

The native-drawing attempt on `3c455ff7573fc2a3dc84ff4983f5593991b246e0`
exposed a second host-renderer limitation. Both independent Android workflows
abort the same test worker inside Robolectric's native
`CanvasContext::prepareTree`, with `SIGSEGV` followed by exit 134. Their 1,224 XML
reports contain 7,733 recorded cases: 7,727 passes, six skips and no assertion
failures. This is **an incomplete, failed execution**, not a passing full suite.
Only two composer records were emitted: the unfocused-disposal case passed, and
the keyboard case was skipped after the abort. Six remaining composer cases and
both `ConflatedRenderPreparationTest` cases are absent. All 88 cases in the 11
changed MCP classes and all 63 transport/negotiation cases passed in both runs.

The next checkpoint returned to explicit legacy graphics and replaced only the
unsupported API 34 BLAST surface factory with a class-scoped shadow. Its surface
objects use Robolectric's existing `ShadowSurface`, including validity and
release tracking. Android's actual magnifier creation, dismissal and teardown,
Compose input sessions and all eight original focus/draft assertions remain in
place. Native drawing and the process-wide draw-property rule are removed. A
separate fixture regression calls the real hidden Android BLAST factories and
checks allocation and release, so a missing shadow registration cannot silently
pass. This fixture does not qualify native rendering or physical-device IME
behavior.

The bounded CI diagnostic summary now recognizes JVM crash signals, native
frames and abnormal worker exits before the generic Gradle failure. Tests cover
the observed crash shape, an exit without an assertion failure, unchanged secret
redaction and ordinary successful output. Raw JVM crash/environment files are
not added to uploads. Reprocessing the actual failed log identifies `SIGSEGV`
and `CanvasContext::prepareTree` as the cause instead of only the later build
failure. Current-head Android execution remains required for the corrected
fixture and for the heap/schema/APK stages skipped after the failed unit step.

The corrected source passes all 400 canonical fast tests, 1,284 literal-regex
checks and 30 resource XML checks; 77 dynamic regex patterns remain explicitly
outside the static check. Kotlin formatting, generated architecture and the
2,244-file / 26-area production inventory validate. The release scope adds only
the two exact diagnostic-script paths, for 1,050 paths in total; its release
identity, base, qualification and limitations remain unchanged.

The actual added shadow, annotations and factory/release regression compile
against SDK 37, JDK 21, Kotlin 2.4.20 and hash-verified pinned Robolectric 4.17 /
JUnit 4.13.2 APIs. This check caught the hidden no-argument `Surface` constructor;
the fixture now invokes that same constructor with Robolectric's
`ReflectionHelpers.callConstructor`. A full local Compose attempt stops during
Gradle plugin resolution, before compilation or tests. The SDK compilation check
does not substitute for running all nine fixture cases in current-head CI.

## Complete-suite bitmap and coroutine follow-up

Both Android runs on `2ddeff93db245b6571d2fde1bd4fd007faec8e77` now finish
the full unit suite without the native worker abort. Each produces 1,225 XML
reports covering 7,742 cases and five ordinary skips. The canonical run records
7,733 passes and four failures; the independent audit records 7,734 passes and
three failures. All nine composer cases are present, including the passing real
BLAST allocation/release regression. Both previously absent conflated-rendering
cases pass. The 88 changed MCP cases and all 63 transport/negotiation cases pass
in both executions. These complete results remain failing gates.

The three common composer failures occur when magnifier capture reaches Compose
vector drawing. Compose invokes the six-argument Android bitmap factory with
display metrics and color space. Robolectric 4.17's legacy bitmap shadow has the
five-argument color-space overload, but lacks that six-argument entry point.
The real Android body therefore reaches an unsupported native allocation and
dereferences a null bitmap. This is distinct from both the original missing
BLAST surface and the later native-renderer crash.

The repair registers an API-34-only bitmap subclass through the affected tests'
`@Config`, without a picker that would replace the explicit override. It adds
only the missing factory, retains Android's rejection of mutable hardware
bitmaps, delegates allocation to the existing legacy color-space factory, and
applies density when display metrics are supplied. Direct Android and actual
Compose `ImageBitmap` regressions exercise the registration and allocation
path. The legacy fixture does not establish native pixel fidelity or device
rendering qualification.

All six focused Android boundary cases pass without skips: three new bitmap
cases, including the actual Compose `ImageBitmap` path, and the three incoming
Surface/Magnifier cases. They compile against SDK 37 and execute on pinned
Robolectric 4.17/API 34 with Compose 1.12.1 and verified dependency hashes. A
negative control runs the identical bitmap tests without the new shadow: both
allocation cases reproduce the CI null-bitmap stack, while the input-validation
case passes. All nine full composer interaction cases still require current-head
Android CI; the focused result does not replace them.

The fourth failure is independent of graphics: the canonical dashboard test
reports an uncaught coroutine exception before its next test starts. The
preceding gateway-selection case calls refresh, which reaches an unstubbed
diagnostic-page read on `Dispatchers.IO`. Its cancellation was not joined before
teardown reset `Dispatchers.Main`. The fixture now supplies an explicit empty
diagnostic page and awaits the ViewModel job's cancellation in each existing
`finally` block, before Main is reset. All original routing, request-count,
cancellation and state assertions remain in place.

All three gateway-status cases pass in a focused host run compiling nine current
production files, including the real ViewModel, monitor and diagnostics facade,
with pinned Lifecycle 2.11.0 and coroutines 1.11.0. Eight explicit host boundaries
cover unrelated infrastructure; an unstubbed diagnostic storage call fails
loudly. Source hashes remain unchanged. The full Android suite remains required.

The concurrent `5acf0eaed06ed34d7972752e40c8ffd733eacea3` gateway-continuity
change is preserved. Its stronger SurfaceTexture-backed BLAST fixture, explicit
surface ownership cleanup, three actual Surface/Magnifier boundary tests, and
all nine existing composer cases are retained. Its durable jobs, conversation
replay, model-specific availability, Room 50 migration and exact release scope
remain part of the combined branch. Authoritative generated Room schema and
full Android checks are required for that combined source.

The combined head's Android generation succeeds, but production compilation
finds one stale profile-card call in `SelectPlatformDialog`: after the card gained
optional gateway parameters, its trailing lambda binds to the final Boolean
parameter instead of `onInsights`. The same two compiler diagnostics appear in
the canonical, catalog, Marketplace, emulator and CodeQL builds. The call now
names each existing argument explicitly. Selection, favorite and insight actions
retain their original bodies; optional gateway presentation retains its defaults.
The production source inventory is regenerated for the corrected call.

The exact Room 50 JSON exported by canonical run `42430153257` is included,
with archive and file hashes verified. Its producer checked out PR merge
`cc51458a6cfc1e5389ce43f7b5e18712ae90c68b`, combining `5acf0eae` with the
recorded `3ac89cea` base. The schema adds exactly eight gateway tables; all 48
existing entity definitions and the views are unchanged. Executing the 18 actual
migration statements in SQLite yields matching columns, defaults, primary keys,
indexes and foreign keys. This structural check does not replace the current-head
Room integration tests and generated-schema CI gate.

The `2ddeff93` API 34 emulator job passed 56 cases in 20 instrumentation groups,
but its performance artifact reports 98 acknowledged starts, two unacknowledged
starts, 98 successful records and one in-progress record: `coverageGap=true`.
Those measurements remain incomplete and `measurement_only` on a nonrepresentative
emulator. API 36 and the separate review-APK job were cancelled after the branch
advanced; their partial output is not a current-head success receipt.

The final combined working source passes all 400 canonical fast tests, 1,304
literal-regex checks and 30 XML checks, with 77 dynamic patterns outside static
evaluation. All five changed Kotlin files pass formatting; architecture and the
2,265-file / 26-area inventory validate, as do 1,146 exact release paths. One
intermediate fast attempt hit two subprocess-startup deadlines during concurrent
compiler validation; all seven deadline/retry cases pass in isolation and the
complete final fast rerun passes without changing timeouts or assertions. The
published follow-up still requires fresh full Android, Room, heap, lint, emulator
and packaged-APK checks.

## Incoming continuity fixture compilation

Canonical run `42431404076` on
`ab2845442fd30e317c4266b78e28c3880ecf851b` passes production compilation and
the exported Room schema gate. Unit-test compilation finds two further fixture
errors in the incoming continuity work, so unit execution, constrained heaps and
the canonical review APK do not run on that attempt.

The persistence fixture's nested MockK answer receiver shadows its mutable
`Preferences.value` with a read-only member. The fixture now qualifies its settings
reads and writes with `this@Preferences`, preserving the interrupted-cleanup branch
and all database assertions. The startup fixture's assistant placeholder now
supplies the required empty `MessageV2.content`; its message, linked-user and run
identities remain unchanged. No production implementation, composer fixture or
existing assertion is altered by these two compilation corrections. Complete
Android execution remains required after this follow-up is published.

The exact preferences fixture compiles against real MockK in a bounded probe.
Its runtime checks confirm stored-state reads, mutation and returned state,
preservation without invoking the transform on interrupted cleanup, and a usable
retry. This uses a minimal DTO boundary and does not execute the eight Room
cases. The startup fix is checked against the actual `MessageV2` source, whose
`content` is required; retained host stubs are not used as compilation evidence.
Both changed test files pass formatting. The parent schema artifact contains no
JUnit reports and reproduces the committed Room 50 bytes exactly. All four
gateway matrix jobs pass on that parent; they do not replace fresh Android
execution for this correction.

Final canonical fast validation passes all 400 tests, 1,304 literal-regex checks
and 30 XML checks; 77 dynamic patterns remain outside static evaluation. The
unchanged 2,265-file / 26-area production inventory, architecture index and
1,146-path release scope validate. No production file or validation threshold is
changed by this follow-up.

## Combined continuity and large-text validation

The concurrent `446f43e396c28cece0f2e6f5d6bfb2cb40bdcf5e` continuity
hardening and `7b346cc9cc0388af9b647d617f89ab8244a2be97` database-fixture
correction are preserved. The latter closes the actual Room database in
`try/finally`, replacing an unsupported database `use` call while retaining all
six backup assertions and the supported cursor `use` calls. Both independent
Android workflows now compile every production and test source successfully.

On that exact combined head, canonical run `42434895712` and diagnostic run
`42434895604` each produce 1,245 XML reports containing 7,902 cases: 7,896 pass,
one fails and five are skipped. The only failure in both runs is
`GatewayProfileAvailabilityTest.largeTextWrapsTheFullNameAndKeepsTheOnlineBadgeVisible`,
at the assertion that the long name occupies more than one line. Neither run
contains the earlier native worker abort. All 202 expected cases across 23 focused
classes are present, including all nine composer lifecycle cases, three bitmap
cases, three Surface/Magnifier cases, three gateway-status cases, two conflated
render cases, 88 changed MCP cases, 69 transport/negotiation cases, and 20 Room
persistence/backup cases. Every one of those cases other than the identified
large-text case passes in both workflows.

The canonical report artifact is `13599668878`, SHA-256
`2a605fbdfae038cf1f028fa2cf989cd93180c845ba4244734ade4687dfe17f1c`;
the independent diagnostic artifact is `13599394828`, SHA-256
`453f9b25e56ae83b093e6beb48f9360a8c07155233d746a02fcd2b5e5d496490`.
The five skips are the unchanged explicit assumptions for the five dedicated
heap tests. The failed ordinary unit step prevents those heap gates and the
canonical review-APK step from running, so neither is claimed as passing. The
Room schema gate passes and the generated Room 50 export remains byte-identical
to the committed schema.

Pinned Robolectric 4.17 sources identify the remaining failure as a font-metric
fixture limitation: legacy `ShadowLineBreaker.nGetLineCount` always returns one,
and legacy `ShadowPaint.measureText` substitutes character-count pixels. The
production profile-name `Text` already allows wrapping without a line limit.
Only the large-text test now selects method-scoped native graphics for actual
font measurement and line breaking. All five test bodies and assertions remain
unchanged, as do the class-level legacy default and all composer fixtures. The
method annotation overrides the class mode only for that test. It does not
enable the separate API 34 real-draw-traversal option involved in the earlier
native composer crash.

A bounded probe against the pinned Robolectric 4.17 Android 14 runtime executes
both modes: native measurement gives the same 33-character name a 499-pixel
width and three complete lines in a 200-pixel layout; legacy measurement gives
33 pixels and one line. Both probe assertions pass, including complete final
text and no ellipsis or overflow, and the real-draw property remains absent.
This validates the platform font-measurement boundary, not the full Compose
card. Fresh full Android and dedicated heap execution remain required after the
method-level correction is published.

The separate review-APK workflow `42434895599` passes all arm64, universal and
x86_64 package gates. Each APK contains 27 DEX files and 2,941 checked dispatch
methods; the largest checked method is 15,900 code units, below the unchanged
16,383 limit, with no checked method over that limit. JNI, packaged runtime,
host alignment and telemetry-component checks pass. Benchmark app and test
compilation pass; benchmark execution and physical accelerator qualification
are not inferred. Lint, CodeQL, repository contracts, reliability, both format
gates, the gateway matrix, installer, Marketplace and APK catalog gates also
pass on this head. Required verification remains failed because of the single
ordinary unit failure.

The scoped correction passes all 400 canonical fast tests, 1,304 literal-regex
checks and 30 XML checks; 77 dynamic regex patterns remain outside static
evaluation. The changed Kotlin fixture passes formatting. Architecture and the
2,266-file / 26-area source inventory validate, as does the unchanged release
scope of 1,154 exact paths. No production code, assertion, timeout, dependency,
memory limit or release boundary changes in this follow-up.

## Verified predecessor and Room 51 integration

Commit `0582f3428736261ee0399e52fc9be4ce4c7db10c` completes both independent
ordinary Android suites: 1,245 reports and 7,902 cases per run, with 7,897 passes,
zero failures or errors, and five dedicated-heap assumptions. All 202 expected
case identities across the 23 priority classes are present and pass, including
the large-text Compose regression. The five heap cases then pass separately:
four verify a maximum JVM heap of 256 MiB and staged diagnostic capture/resume
verifies 64 MiB. All 11 dedicated executions, including six repeated supporting
cases, pass without skips. Canonical schema, APK/runtime/DEX checks and Required
verification pass. API 34 and API 36 each complete 56 cases in 20 invocations,
with 100 acknowledged and successful diagnostic operations and no coverage gap
in either measurement-only emulator sample. The separate three-APK review job,
canonical lint, CodeQL, gateway matrix and contract gates also pass.

The canonical and audit unit artifacts are `13600936144` and `13601037373`,
with SHA-256 `9232c74dbd3ac69f5efb19ba76c02d15a35adbc2f98398c6543b401696e53ea3`
and `b1ab9cf9e38f994abe75a02fea5042fb6797e98f1afe775959a866950cc855bb`.
Canonical final logs, including the individual heap XML files, are artifact
`13600739363`, SHA-256
`21bac3ecab11dc71184e11163970459f294bd1fe55598da3e3c795b6869c0273`.
Both Android logs identify PR merge
`6f9b6abcd6ba0c3b0162fe184f65682c98a09234`; its tree equals the validated
branch tree. The audit also finishes its debug APK build, then its final lint
step is cancelled when the branch advances. That unfinished audit lint is not
reported as a failure or a completed pass.

The subsequent `66ac834e09806c187e6fa92fad8d82c837be1ed5` gateway integration
and `6058217056cec0fecc5bdea0733ae33a10baf45e` provenance correction are
preserved. They add the server-backed conversation browser, persistent job and
transcript handling, and Room 49/50-to-51 migration. The second commit only
corrects the package source reference; all 177 gateway file hashes remain equal
and match the checkout. The original core task/catalog/evidence, MCP and
diagnostic-export implementations are unchanged. The nine composer, three bitmap
and three Surface/Magnifier cases remain byte-identical; the five availability
case identities and scoped native-font correction are retained with the new UI
API. Changed coordinator admission and recovery preserve the original gateway
binding, uncertain-command reconciliation and explicit cancellation boundary.
These are source-review findings; predecessor CI does not qualify the later
Room 51 integration.

Two demonstrated integration gaps are corrected in this follow-up:

- The new `GatewayProfileCardInstrumentedTest` was compiled but omitted from the
  acceptance runner. The runner now executes both large-text/light and dark/RTL
  cases, captures their own raw log and process status, propagates failure, and
  requires both exact successful test packets. Every earlier invocation and
  test remains selected: 21 invocations and 58 cases replace 20 and 56. The
  explicit packet list grows from 54 to 56; two additional pre-existing cases
  still execute as members of their selected classes. Twelve existing shell,
  readiness and strict packet-parser tests pass, as do shell syntax and diff
  checks. Existing limits and assertions remain unchanged.
- A retained gateway conversation reaches the shared submission guard before the
  ordinary chat guard. The shared guard rejected "without commits" but missed
  "without committing". It now recognizes both forms before capability admission,
  storing a command or sending an operation. The regression exercises the actual
  bound repository path and proves no admission, store mutation, create or
  control call occurs. Read and planning scopes remain allowed.

The current repository, model and original submission test sources are compiled
against matching cached Android collaborators with the pinned Kotlin, Compose
and serialization 2.4.20 compilers, using the existing Byte Buddy 1.18.2 startup
agent. The unfixed helper reproduces exactly two failures in eight cases; the
fixed helper passes the identical eight cases with no skips or source drift.
An initial probe omitted the Compose compiler plugin and failed at the cached
ABI boundary before tests; that setup attempt is not product-failure evidence.
No substitute production implementation is used. Fresh complete Android, Room
51, heap, 58-case emulator and package verification remain required after the
combined follow-up is published.

The combined follow-up passes all 400 canonical fast tests, 1,305 literal-regex
checks and 33 XML checks; 77 dynamic regex patterns remain outside static
evaluation. Changed handwritten Kotlin files pass formatting. Architecture and
the regenerated 2,291-file / 26-area source inventory validate. The existing
release scope validates 1,320 exact allowed paths, with no added path or change
to the release identity, base, qualification or limits. Both incoming-parent
Android workflows also pass production and unit-test compilation; their active
full suites are not treated as completed validation of this follow-up.

## Backup migration route follow-up

The `0b5661fbe9652841f2f1f6968bcf5c697db4ba99` follow-up passes all 58
acceptance cases in 21 invocations on both API 34 and API 36. Both new gateway
profile-card cases have independently checked successful packets. Each retained
emulator artifact reports 100 acknowledged operations, 100 successful journal
records and no coverage gap; these remain measurement-only emulator samples.
Both production and unit-test compilation phases pass. The exported Room 51
schema is byte-identical to the committed schema. The full unit runs are then
cancelled after the branch advances, but both terminal logs contain two genuine
backup failures before cancellation. Their partial artifacts contain no JUnit
XML, so they do not establish a completed suite, priority coverage or heap pass.
The separate review APK build and device-test compilation pass, then benchmark
compilation is cancelled before packaged runtime gates or artifact publication.

The incoming `525ff8bce34c27f0904591d263b8d4a74a0b280b` recovery/account-scope
changes and `ba6c275291fd0c4721866aec1f7d1cc741d58705` manifest correction
are preserved. They retain queued cancellation and finalization failure receipts,
verify original account authority for Stop and cached history, keep authorized
offline history accessible, and exclude device-local account proof from portable
backups. All 177 package file hashes match the checkout. The shared no-commit
correction, both gateway card device cases, the acceptance runner and the original
F01–F23 implementations remain present. Source review finds no additional blocker
in the incoming authority, backup-proof exclusion or UI changes; their final
runtime qualification still belongs to the current combined head.

Both observed failures are in `CompleteBackupManagerTest`:
`olderModernBackupMigratesNewTablesAndColumnsBeforeRestore` and
`version30BackupAddsProfileAttributionWithoutLosingRequests`. Their historical
fixtures lower a current database's version but leave later gateway tables in
place, causing the obsolete version-50 migration to create an index on absent
`scopeId`. The fixture helper now removes the eight post-version-30 gateway
tables; every existing test assertion is retained.

The production restore path has a separate real defect: choosing the first
registered migration at each version takes 49→50→51 instead of Room's direct
49→51 route. Even a genuine older backup consequently acquires obsolete archive
tables that the fresh-schema restore guard correctly rejects. Restore now resolves
the complete path with the pinned Room `MigrationContainer.findMigrationPath`
before applying migrations. Missing paths still fail before migration writes,
and all source validation, foreign-key checks, staging transactions and the guard
against discarding retained gateway evidence remain unchanged. Actual version-50
archives retain their existing explicit recovery boundary.

The focused comparison recompiles the actual manager and both original backup
test classes with pinned Kotlin/Compose/serialization 2.4.20, JDK 21 and the existing
Byte Buddy agent. It executes real Room 2.8.5 generated schema-51 code under
Robolectric 4.17 / API 34 against 256 byte-matching cached supporting sources.
With identical corrected historical fixtures, the unfixed manager produces exactly
the two expected retained-archive failures among 27 cases. The corrected manager
passes all 27 cases: 21 complete-backup cases and six gateway-backup cases, with
no source drift. No replacement production implementations are used. An initial
host setup used a scratch working directory that could not locate the existing
Marketplace README fixture; both authoritative comparisons use the repository
working directory. The newer additive cached-conversation DAO query is not used
by these cases and remains subject to fresh generated-code CI.

All 400 canonical fast tests pass, along with 1,305 literal-regex checks and 33
resource XML checks; 77 dynamic regex patterns remain outside static evaluation.
All three changed Kotlin files pass formatting. Architecture, the regenerated
2,292-file / 26-area inventory and the existing 1,328 exact allowed release paths
validate, with no release identity, qualification, limit or allowed-path change
in this follow-up. The expected current priority set is 610 cases across 61
classes, now including the complete-backup regressions explicitly; the inferred
7,994 ordinary cases require confirmation from fresh complete XML reports.
Full Android, constrained heaps, device, APK and aggregate checks remain required
on the published combined source, rather than inherited from a superseded run.

## Polling lifecycle and integration follow-up

The `bcb72647703b8840ef4ae869804f90399bd0c97f` backup repair remains in the
combined branch. Before that head was superseded, the audit log records all 21
complete-backup cases, all six gateway-backup cases and the new exact-account
cached-conversation DAO case passing against freshly generated Room code. The
canonical exported Room 51 schema still matches the committed schema. Both full
unit jobs were subsequently cancelled and their artifacts contain zero JUnit XML;
these individual observations do not establish complete unit or heap qualification.

The canonical log also exposes one genuine failing lifecycle regression:
`GatewayJobsViewModelTest.leavingTheVisibleScreenStopsPollingWithoutStoppingTheJob`
observes two refreshes instead of one. Its first refresh resumes inline from a
real I/O-thread state emission, while the second is resumed by the test scheduler
advancing virtual time. The unconfined test coroutine therefore races the test's
cancellation observer. That test now runs its complete scope with
`StandardTestDispatcher(dispatcher.scheduler)` and lets the polling coroutine
inherit it. Both polling and the cancellation observer therefore return to the
same scheduler after I/O-triggered emissions. Scheduling only the polling coroutine
was insufficient: an initial 11-case pass was followed by the same assertion
failing on the tenth targeted repeat, so that partial correction was rejected.
The production ViewModel is unchanged. Every existing
method and assertion remains, including exactly one refresh, zero Stop calls,
and 30 seconds of virtual time after cancelling and joining the visible scope.

The same superseded head's API 36 run completes 58 cases in 21 invocations, with
both gateway-card packets and a locally verified artifact digest. Its diagnostic
sample contains 100 acknowledged and 100 successful operations with no coverage
gap. The separate review APK workflow passes all three packaged variants, runtime,
JNI, privacy and native-alignment gates. Each variant checks 2,946 dispatch methods;
the maximum is 15,834 DEX code units against the unchanged 16,383 limit. Benchmark
compilation succeeds; no benchmark execution is claimed.

API 34 stops before instrumentation at Android's application-readiness barrier
(70 of 71 participants). Retained process, window and ANR evidence shows no app
crash or ANR, and zero cases execute. One failed-job retry preserves the same
source identity, assertions and timeouts, then is cancelled during compilation
when the branch advances. It also executes zero cases. The successful API 36 job
is reused by that retry, not counted as a second execution.

The incoming `c64ffef97f02eee19f7fe968f28ac2599774f5ff` integration and
`b18ddc95c223e4703c07af44334bb8db547669bb` manifest provenance update are
preserved. The source review covers the following additions and retained guards:

- Exact-model reasoning preflight and explicit recovery controls retain the draft,
  settings snapshot, generation and attempt identity. Rechecking does not send
  automatically or change the model. Brief helper/rewrite roles preserve accepted
  ON/AUTO/OFF instead of manufacturing OFF; explicit OFF still rejects an exact
  route without verified control support. Maximum preference alone is removed.
  Trusted pre-inference rejection cannot erase an earlier uncertain receipt or
  measured output.
- Saved-work admission causes, bounded invocation accounting and checkpoint writes
  retain original failure/receipt identity. Safe-read connection retry remains
  GET-only; mutation and stream paths are not replayed. Exported diagnostic fields
  remain allowlisted and unmeasured settings remain unknown.
- Canonical action identity includes task, authority, selected tool suffix,
  repository and exact lease/content/cursor/live-intent fields. Owned-fixture
  recovery requires durable cleanup intent, original-account access to the
  original immutable commit and fresh exact-ref absence. Pagination and catalog
  coverage retain issued selectors and explicit omissions.
- Composer ownership and all existing focus/bitmap/surface assertions remain
  unchanged. Voice callbacks verify recognizer identity and generation; repair
  survives pause and requires deliberate action before recognition resumes.
- The acceptance script retains all previous 21 invocations and adds two bound
  GitHub transport cases in one invocation: 60 expected cases across 22 invocations
  per API. Both new cases require exact successful packets and retained receipts
  tied to the installed app revision. They execute production binding/client code
  against Ktor MockEngine and explicitly do not claim live-account side effects.

The actual-source comparison compiles the current ViewModel, test and six required
collaborator/type files against 13 byte-matching cached direct supporting sources,
using the pinned Android/Robolectric runtime. The original 11-case class reproduces
exactly the observed lifecycle failure. The final complete-scope correction passes
all 11 cases and 20 consecutive executions of the same lifecycle method, retaining
its original setup, teardown and assertions. Those repeats are overlapping
stability checks, not 20 additional unique tests. Production sources and the final
snapshot have no drift. The original repository, conversation and control mocks
remain; this focused result does not qualify actual network or generated Room
execution. The first polling-only correction and its failed repeat are retained
separately instead of being counted as a complete pass.

All 404 canonical fast tests pass, with 1,328 literal-regex checks and 33 resource
XML checks. The 77 dynamic regex patterns remain outside static evaluation.
Architecture and the unchanged 2,306-file / 26-area source inventory validate.
The exact current priority set contains 962 cases across 99 classes, retaining
all previous 610 identities in 61 classes. The source-inferred ordinary count is
8,095 and requires confirmation from complete fresh XML. Four incoming reasoning
case renames are recorded explicitly; none removes an earlier priority identity.

The incoming `b18ddc95` head also completes all 60 API 36 cases in 22
invocations before this follow-up is published. Both new bound-client receipts
and the journal sample identify merge producer
`9f86828405c4bfef0133a9b944ffbf562447fd3b`; its independently inspected parents
are the exact base and incoming head, and its tree matches that head. The retained
artifact digest verifies. Both new receipts explicitly report stub execution with
zero live-account effects; the journal reports 100 acknowledged / 100 successful
operations with no coverage gap. This is complete device evidence for the incoming
parent, not a full-suite qualification of the subsequent test correction.

All 178 advertised gateway package files match their hashes. The existing scope
contains 1,374 allowed paths; release identity, base, required workflows and limits
remain unchanged. The incoming integration retains all earlier disclosed limits
and adds an explicit live/device qualification boundary. Fresh complete Android,
Room 51, constrained-heap, 60-case device, packaged APK and aggregate evidence is
required for the published combined tree.

The incoming parent also completes its three review APK variants with all runtime,
JNI, privacy and native-alignment gates passing. Each checks 3,088 dispatch methods;
the maximum remains 15,834 code units below the unchanged 16,383 limit. Its
benchmark app and regressions compile; benchmark execution is not claimed.

Its API 34 run executes all 60 device cases: 59 pass and one conversation-glass
case times out before pixel comparison. The retained sharp image shows an
unobstructed fixture, the activity remains resumed, and no crash or ANR is recorded.
The capture fence coincides with a 2.657-second HWUI frame, including 2.258 seconds
between issuing draw commands and swapping buffers, exceeding Compose's existing
two-second frame-commit wait. API 36 on the same tree passes all four blur ratios.
This does not establish a failed blur threshold, a separate capture synchronization
defect or a proven external cause for the late frame. The renderer, visual
assertions and frame-commit fence stay unchanged and require fresh device
qualification after the combined fixture correction. Both new bound-transport
receipts in the failed API 34 artifact independently verify, while the CI final
verifier correctly remains failed at the earlier glass packet.

### Gateway metadata read-client fixture follow-up

A final caller/fixture review catches two shared helpers that still stub only
`NetworkClient.invoke()`: `OpenAIRecoveryTransportTest` and
`GatewayCommandDispatchTest`. The incoming production code correctly routes
capability discovery and saved-result GETs through `gatewayReadClient()`. The
non-relaxed mocks therefore throw before their HTTP engine sees those reads,
preventing the existing tests from exercising discovery, retained-result recovery
and command-reservation behavior.

The complete incoming-parent preview run reports 8,095 cases, with 12 failures
confined to these two classes and five dedicated-heap assumptions skipped in the
ordinary run. A focused actual-source negative comparison reproduces both missing
boundaries: no capability request reaches the engine and no reservation callback
is reached. Those checks execute their original assertions.

Both helpers now return their existing closed HTTP fixture from
`gatewayReadClient()` as well. This is one added stub per helper. All 16 transport
and seven command-dispatch method identities and assertions remain unchanged,
including exact paths and methods, one-time mutation, original command/payload,
reservation-before-POST, cancellation and uncertain-work no-replay checks.
Production pool separation, GET-only retry, mutation/stream behavior and timeouts
are unchanged. The separate pool-isolation regressions remain in the suite.

The final focused comparison compiles the same 18 current production files and
two original test files against 94 byte-matching cached direct supporting sources
on the pinned full Android runtime. The two selected original negative cases
reproduce the missing capability request and missing reservation callback. With
only the two fixture stubs added, both full original classes pass: 23 of 23 cases,
zero failures or skips, normal process exit and no source/support drift. This
executes actual Ktor HTTP fixtures and the real transport/discovery/admission code;
it does not establish live socket, generated Room or unrelated UI behavior.

The incoming canonical unit job independently finishes with the same 12 failures
as preview, confined to these two classes. The preview artifact has 1,273 complete
XML suites: 8,095 cases, 8,078 passes, 12 failures, zero errors and five expected
ordinary-run heap assumptions. All 99 priority classes and 962 identities are
present; 950 pass and the same 12 fixture cases fail. Those parent failures remain
recorded separately from the corrected local 23-case pass. The audit's completed
XML independently reports the same 8,095 cases and exactly the same 12 failures,
with no additional failing identity; its trailing APK build is still active at
this publication snapshot. Fresh complete unit,
constrained-heap, device and aggregate checks are required after publication.

The final combined checkout passes all 404 canonical fast checks again, plus
1,328 literal-regex and 33 resource-XML checks. All three changed Kotlin test files
pass formatting with stable hashes. Architecture, the 2,306-file / 26-area
inventory and the existing 1,374 exact allowed release paths validate. No
production file, test identity, assertion, timeout, heap or DEX threshold,
dependency verification or release qualification is changed by these fixture
corrections. The five changed files are the three existing test fixtures and
these two existing repair ledgers.

## Measurement boundaries

The gateway reports `completion_buffered` because generated content still passes
complete-result processing and tool validation before delivery. Progress events
and actual ASGI forwarding are independently observable. A completed awaited ASGI
send is not proof of a physical socket or reverse-proxy flush; that fact stays
unmeasured.

Compose draw completion is measured for the rendered run stream with run,
ownership-fence, part, process, and monotonic-time identity. It is not a physical
display-presentation receipt. The renderer cannot currently bind every verified,
combined, or saved answer to one provider invocation. Consequently per-HTTP
`first_ui_paint_ms` remains null unless that lineage is explicitly available;
the diagnostics UI no longer pairs unrelated first-provider and first-draw events.

Backend-reported model names and a route digest do not establish loaded weight
identity, runtime implementation, physical upstream machine, accelerator use, or
effective reasoning controls. Those remain `NOT_MEASURED` without authoritative
acknowledgments. No fallback model or relaxed authority policy is introduced.

Frame and export instrumentation does not establish a device speedup. Matched
debug/release traces at the same refresh rate, a running APK with authorized live
GitHub fixtures, and compatible-hardware qualification remain separate acceptance
work. No OOM, ANR, memory leak, or graphics-driver cause is inferred from the supplied
archive.

## Validation record

Focused JVM harnesses compile current production implementations with explicit
Android/service boundaries. They do not establish a full Android build. The
companion checkpoint records final run counts and canonical validation results.

- Task/catalog/evidence/session suite: 249 passing tests, compiling 90 current
  production files and 29 repository test/helper files, with 27 explicit host
  boundary files (including two extracted session checkpoint methods) and no
  source drift. Regressions include actual `GitHubVerifiedSession`,
  `GitHubEvidenceBook`, task continuity, budget parsing, readiness, operation facade,
  provenance, and acceptance code.
- Focused schema/admission suite: 225 passing tests (overlapping the task/evidence
  suite). Ordinary schemas measure 2,990 UTF-8 bytes / 997 estimated tokens under
  the unchanged 1,000-token limit; the admitted prompt is 1,436 tokens under 1,500.
- Actual HTTP/workspace branch suite: 23 passing tests, including exact leases,
  explicit review targets, missing observed SHAs, and default-branch rejection.
- Complete gateway gate: 49 Python suites, 560 passing cases and one declared
  network skip; all seven browser bridge tests pass. The 50,000-event / 782
  FULL-sync-commit durable stress test passes with unchanged watchdogs.
- Gateway/provider focused Kotlin suite: 30 passing tests; admitted-request
  provenance and retry retention: nine passing tests. Five receipt cases overlap
  between those two batches. The full ledger and provider-adapter integration
  regressions remain part of Android CI.
- Gateway focused Python suites: 73 passing tests, comprising 16 message-contract,
  25 completion-recovery, 29 actual gateway-route, and 3 ASGI-observation cases.
- Export/timeline core: 54 passing cases and one assumption-skipped heap case in
  the ordinary run. The separate real 64 MiB heap gate passes capture, resume, and
  hashes for 20,000 records / 163,840,000 source bytes.
- Journal/coverage/draw: 28 passing cases; actual staged-export adapter: 17 passing
  cases; bounded omission/frame helpers: 12 passing cases. These suites overlap
  and must not be summed into a unique-test count.
- Canonical `bash scripts/validate-local.sh fast`: 398 script tests and every
  fast gate pass, including 1,274 literal Android regex checks and 30 XML files.
- Full Android compilation, Room/Compose/provider integration, DEX-size checks,
  and aggregate required verification belong to CI on the published commit.

Generated architecture and app-audit source inventories, the complete gateway
package manifest, and the exact allowed release paths are refreshed. Dependency
checksums, configured build-memory limits, release identity, and the warning boundary of 16,383
DEX code units remain unchanged.
