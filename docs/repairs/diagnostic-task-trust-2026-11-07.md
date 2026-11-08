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
