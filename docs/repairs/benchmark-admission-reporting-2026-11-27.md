# Benchmark preparation and evidence repair

## Scope

The implementation starts at PR #2651 head `d45c75f0161c8377ff9041e42c4e8715c6eed1f9`,
on `feature/windows-native-benchmark-kit`, against main
`6435462ac6f306d63b720205a9f2aafc33de1e4d`. It preserves the concurrent voice,
GitHub, and Gateway changes already on that branch.

The supplied benchmark export contained two stopped Standard runs and one
unfinished run with a blank model selection. None contained fixture dispatch
evidence. The stopped runs accounted for their unstarted fixtures, but represented
preparation as a failed first task and repeated its cause across dependent cases.
The same accounting rules apply to resumed engineering setup, and interrupted
preparation is terminalized without inventing its final duration.

These observations do not establish a provider outage, model quality, the identity
of a resource blocker, or device performance.

## Behavior

- Check enabled profile, selected model, and required authenticated credentials
  before joining the isolated measurement queue. UI and execution use the same
  configuration predicate; credentials are checked at the authenticated boundary.
- Bound benchmark resource admission to 30 seconds independently of provider execution,
  including the advanced local runtime comparison. Retain
  preparation phase, queue duration, queue limit, and generic hashed blocker
  labels. Isolation and native cleanup fences remain in force.
- Persist preparation separately from fixture samples. Explicit stop, skip,
  service interruption, queue expiry, and execution failures keep distinct causes.
  Dependent cases have their own not-started disposition and reference the root
  event rather than repeating it as a new provider failure.
- Retain planned fixture hashes and validator versions on unstarted cases, without
  fabricating execution timestamps or a deadline. Optional observed timestamp
  fields distinguish new receipts from historical compatibility fields.
- Derive the non-warmup denominator from the accepted manifest. Missing historical
  plans remain unknown. Outcome counts partition accounted cases; not-started is
  a separate observation and is not added to that partition.
- Exclude run preparation from task effort while retaining it in setup and run
  wall time. Actual per-case admission rejection remains operational task effort.
  Root-incident counts and affected-case counts have separate meanings.
- Keep evidence membership separate from numerical and ranking eligibility.
  Interrupted or unstarted cases cannot create a measured zero quality score.
  Plan completion is labeled as progress rather than answer accuracy.
- Show durable waiting and explicit user-stop states. Admission request IDs do
  not establish an HTTP send; response evidence and fixture dispatch are labeled
  separately. Standard mode explains its own fixture/timing coverage.
- Redact endpoint addresses embedded in friendly profile labels in safe exports,
  as well as structured endpoint fields. Planned validators and validators with
  actual validation receipts are exported separately.
- Reuse the account repository discovery predicate across GitHub scope decisions,
  and prefer an exact discovered tool name over prefix-matching siblings. The
  existing advanced tool-window policy and its concurrent repair are preserved.

## Concurrent CI regressions

The starting head also failed three tests. A protected diagnostic scalar limit
was reported as malformed JSON; it now preserves the size-limit omission reason
without publishing unauthenticated data. Coroutine stack-trace recovery can copy
a capacity-deferral exception, so its regression checks the retained causal
identity rather than requiring the wrapper instance to be identical. The GitHub
saved-receipt test now expects the current partial-coverage wording while retaining
its blocked outcome, missing-tool reason, and saved source-read assertions.

The first hosted pass at `238f4c5` compiled production sources, then caught four
new test fixtures missing the required `PlatformV2.name` argument in three
benchmark test classes. The correction supplies explicit profile display names;
full hosted checks must qualify the corrected commit. Preview run `46106276846`
and Marketplace run `46106276930` retain the original compiler evidence.

## Validation and release

Behavioral tests cover queue admission and cancellation, manifest accounting,
preparation/effort separation, causal failure counts, eligibility, safe exports,
and presentation. The accompanying task checkpoint records completed local
validation. Full Android, lint, native, Windows, emulator, and publication gates
must pass on the exact integrated commit before release.

This repair does not claim new physical handset measurements, live model quality,
GPU/NPU qualification, or completion of the broader Unified Reliability v2
program. Existing scoped release limitations remain applicable.

## Concurrent integration follow-up

The branch was fast-forwarded through `afbd5c65483e68ded391f15d31ac7ec87d9f7d3d`
and `fc0f9c62dfb9e86b36c2647d39cb5040c12b1748`, preserving the other workers’
GitHub restoration/finalization and Pocket streaming/caption implementations.
Independent review identified two integration defects to correct:

- A screen fallback could mount the original navigation room after the ViewModel
  rejected its binding or privacy. Presentation now requires a resolved accepted
  room. Restoration failures retain the selected identity, expose retry, preserve
  coroutine cancellation, and cannot authorize a blank replacement. An unchanged
  accepted conversation remains mounted during unrelated settings refreshes.
- A caption revision could shorten a word behind the playback watermark while
  leaving a stale queued end. A later valid cue then raised `STREAM_CUE_OVERLAP`
  and interrupted audio. Reconciliation preserves already accepted presentation
  identity and observed end boundaries. An interval that cannot remain usable is
  omitted without inventing new timing or discarding its source-end guard.

The first `fc0f9c6` hosted builds also stopped on an absent verification entry for
`kotlinx-coroutines-guava-1.8.0.module`. The 3,867-byte module was fetched from
[Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-guava/1.8.0/kotlinx-coroutines-guava-1.8.0.module)
and matched both publisher checksum sidecars. Its SHA-256 is
`da3a1b4f5406c60b2e203ceec0813f1caef1017842d965aeb7f60a35d73a21e5`.
Only that absent module entry is added; existing verification configuration and
trusted artifacts are unchanged. The local Gradle dependency-resolution command
completed successfully with cached core-domain compilation. This does not qualify
Android compilation or the full unit suite.

The task checkpoint records the focused regression results and their boundaries.
Every integrated revision still requires current hosted PR checks, a green merged
main revision, and verified publication of both requested packages.

The final combined canonical host validation passed 863 tests with one expected skip, generated references/inventory, dependency declarations, package integrity, secret scan, Android regex and resources. Caption and presentation source hashes still match their execution receipts. Independent reviews found no further actionable issue in the corrected caption bounds and explicit/implicit conversation pin paths. Full Android/ViewModel and native hosted checks remain pending at the commit that publishes these repairs.

### Final CI integration follow-up

The shared branch subsequently added microphone/listening changes in `1b133fb`, its receipt-whitespace normalization in `5098014`, and unknown Combined append diagnostic evidence in `bcca87e`. All were preserved. Independent lifecycle, privacy, ownership and no-replay reviews found no further actionable issue.

The preceding hosted Android run at `e28b5df` compiled production and unit-test sources and passed the early regression selection. These results diagnose the original fixture correction; they do not replace current-head qualification. Its history credential scan reported six public declaration identities twice, including a synthetic merge. Exact upstream revision, immutable source blobs, a reproducible source extract and approved dependency extraction established that they are non-credential values. Six exact path/field/value/whole-line declarations and 48 new scanner controls retain detection of different values, paths, fields, surrounding content and adjacent credentials. All 160 controls passed; the approved AAR-to-member byte proof was reproduced.

The expanded independent fixtures exceeded the existing integrity-wrapper deadline when run serially. Bounded four-worker scheduling preserves deterministic results and every original timeout and assertion. Intact and restored histories passed with all 160 controls; a deliberately missing historical blob failed before scanner completion. Canonical validation on the combined source passed 863 tests with one expected skip. The unchanged full-history scanner subsequently passed against the complete local reachable graph at `d652558c`: 6,723 commits, all 160 declaration controls, zero findings, and complete finding identity reporting. Hosted CI has a broader ref population and must qualify the final head independently.


### Shared corrective commit reconciliation

Concurrent commit `af8fe74671f0572e5ea5bb6341d26da4479c8b6d` independently
published the same investigation tracker binding and public LiteRT option recipe.
Its production code and additional required-tool uncertainty projection were
preserved after independent review. The existing `LoopState` tracker is passed
through the batch helpers; the additional Continue regression checks that restored
stale progress cannot silently reset between batches.

The preserved predecessor full Android suite ran 15,631 tests: 15,623 passed,
six were skipped and two failed. Both failures were stale fixture expectations:
an admission identity alone cannot establish delivery, and a requested report
with no retained answer cannot qualify as partial delivery. The corrected tests
retain explicit dispatch rejection and publication evidence, add independent
dispatch-only and HTTP-response-only controls, and deliver a real retained PARTIAL
report after expiry without provider or tool execution. The three focused metric
regressions pass after reconciliation; full report-session execution remains a
hosted Android gate.

The predecessor lint failure was a testing-only LiteRT API call. The adopted
public helper requests GPU alone or NPU plus the public NONE marker, preserving
the strict native mask and Qualcomm policy in the pinned 2.4.1 artifacts.
Independent ARM64 and x86_64 JNI inspection establishes that NONE adds no native
flag. This is a version-bound compatibility recipe, with dependency verification
and actual-SDK tests, not a cross-version promise or a measured device placement
claim. The complementary proof records its exact source and artifact identities.

The early Android regression stage now includes delivery evidence, retained
reports, restored investigation progress and pinned SDK options. Its isolated
results remain separate from the complete suite and coverage gates. All incoming
assertions, the scanner's duplicate-case guard, original timeouts and every
scoped release limitation are retained.


The final reconciled SDK host check compiles the full incoming runtime and public
helper against the approved dependency and passes all four original tests with
the additional pinned enum-identity assertion. The full local reachable-history
check at `af8fe746` passes all three integrity cases, all 160 declaration controls,
and reports zero findings over 6,724 commits with complete identity reporting.
An earlier combined invocation failed the intact fixture before completing its
controls; the isolated rerun passed without a code, timeout or assertion change.
Its first failure cause is not established, and current-head hosted scanning
remains mandatory.

Canonical validation of the reconciled functional tree passed 863 tests with one
expected skip, all generated references, 2,350 literal Android regex patterns
with zero errors, and 38 XML resource files. Dynamic patterns and physical device
execution retain their existing qualification boundaries. The accompanying
checkpoint records source-bound local results; green PR and main checks and
verified Android plus Gateway publication remain required before completion.
