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

## Validation and release

Behavioral tests cover queue admission and cancellation, manifest accounting,
preparation/effort separation, causal failure counts, eligibility, safe exports,
and presentation. The accompanying task checkpoint records completed local
validation. Full Android, lint, native, Windows, emulator, and publication gates
must pass on the exact integrated commit before release.

This repair does not claim new physical handset measurements, live model quality,
GPU/NPU qualification, or completion of the broader Unified Reliability v2
program. Existing scoped release limitations remain applicable.
