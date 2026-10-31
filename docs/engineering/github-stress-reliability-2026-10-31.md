# GitHub stress reliability follow-up

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Base: `main` at `9781b63c72bf3a2a4786ede07f0d9e094cd07db2`
- Working branch: `fix/github-stress-reliability-2026-10-31`
- Intent: address capacity and concurrency gaps exposed by reviewing the supplied
  benchmark, strengthen revision-bound CI observations, and improve coding context.
- No parent PR; this branch starts from the merged contracts release.

The supplied benchmark is user-reported evidence. Its active-branch deletion and
missing-watch-ID failures are successful safety checks, not defects to remove.
It does not establish exhaustive stress coverage or end-to-end correctness.

## Work checkpoint

Source inspection identified oversized commit patches, loss of error classification
on saved pages, unbounded single-flight subscribers, priority starvation, and a
missing final PR-head fence for subscriptions that specify both PR and run IDs.
Implementation and validation results are recorded below as they complete.

The app's existing execution journal and engineering task store remain the source
of truth for in-app writes. This repository task has no connection to a running
Android task store and does not fabricate or resolve any prior mutation receipts.

## Implemented changes

| Area | Change | Behavioral coverage |
| --- | --- | --- |
| Commit inspection | Default to 30 file summaries; admit 1–100 files per page; opt into patch excerpts with 2,000 bytes per file and 32 KiB per page; preserve immutable SHA and options in continuation calls. | Large 100-file commit, multibyte patches, missing patches, empty pages, facade continuation and request parameters. |
| Result delivery | Preserve upstream error codes on saved pages and capacity receipts, preserve numeric JSON types, escape continuation pointers, stop fragment allocation at the retained-byte limit, and retain task/execution identifiers. | Saved error pages, Unicode reconstruction, large numeric scalars, escaped pointers. |
| Capacity recovery | Offer a narrower immutable commit-summary read with the same starting file offset. A one-file summary that still exceeds capacity does not advertise a repeating recovery call. | Page-size conversion, pinned SHA, schema validity and loop prevention. |
| Request scheduling | Bound waiting shared-read subscribers separately from the physical queue; release subscriber slots on cancellation; give old queued work a turn after bounded priority bypasses. Expose queue occupancy, high-water mark and capacity rejections. | 1,000 simultaneous identical reads, cancellation isolation, bounded priority bypasses and cancellation after permit transfer. |
| Write admission recovery | Use a typed queue-capacity rejection, retain its error code, and record the individual undispatched HTTP write as rejected. Preserve earlier completed mutation receipts and keep compound-operation/lost-response failures uncertain. | A full queue sends no write; a later explicit attempt succeeds once without replaying an earlier mutation; compound failures remain fenced and the tool error does not erase earlier dispatch evidence. |
| CI watches | Validate supplied watch identifiers before backend dispatch; re-read the PR head after fetching an explicitly paired workflow run. | Invalid/missing IDs, concurrent PR push, stable paired watch and independent run watch. |
| Coding context | Discover static relative JavaScript/TypeScript imports and directory entry points against the pinned tree; cache repeated lexical import lookups; stop at the scan bound and disclose incomplete coverage; recognize common Python, JS/TS and JVM test names. | Relative paths, repository escape, root package wildcard, import stress and test naming. |

Safety invariants remain in force: active/default/protected branch deletion guards,
exact-patch staging, native write approval, immutable revision checks and mutation
receipt reconciliation. No remote mutation is repeated to recover presentation data.
The temporary benchmark branch is outside this change and has not been deleted.

## Validation checkpoint

- `bash scripts/validate-local.sh fast`: passed, including 269 host tests, CI policy,
  secret scan, Android regex/resources, icon and QNN provenance checks.
- ktlint 1.3.1: formatting applied and checked for changed Kotlin files.
- Standalone JVM compilation with Kotlin 2.4.20 and the repository's coroutine/JSON
  versions: all 9 tests in `GitHubRequestSchedulerStressTest`,
  `GitHubRequestCircuitTest` and `GitHubReadRetryPolicyTest` passed on JDK 17.
- A further 14 isolated JVM tests passed for commit summaries, result views and
  code context using the production sources and verbatim extracted supporting
  declarations/helpers. This is unit-level evidence, not Android integration.
- All 13 execution-journal tests passed against the actual journal, state store
  and scheduler with inert Android context/annotation/logging fixtures; Android
  keystore access was deliberately unavailable. This covers the new queue rejection
  and existing lost-response, restart, eviction and no-replay behavior.
- Added 23 behavioral regression tests covering the changes above.
- Full Android validation requires JDK 21 and Android SDK 37. This host has JDK 17
  and no Android SDK; the Gradle wrapper also cannot reach its distribution through
  the JVM network configuration. Full app compilation and unit tests are delegated
  to the existing PR CI rather than claimed as locally completed.

These changes do not establish device execution, end-to-end tool perfection, or
merge readiness. The draft PR must be evaluated at its exact head revision.

## Attached diagnostics follow-up checkpoint

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`; base branch: `main`.
- Base SHA reported by PR #766: `9781b63c72bf3a2a4786ede07f0d9e094cd07db2`.
- Starting branch head: `f2f21b8c05f34cf5963123ff74ada9342f7d9ad6`.
- Working branch: `fix/github-stress-reliability-2026-10-31`; existing draft PR: #766.
- Evidence: attached diagnostics from version 0.9.65.0/build 133 at the base SHA,
  5,275 records. No raw user diagnostics are committed.
- Intent: eliminate saved-page argument repair loops, bound finalization when a
  provider ignores disabled tools, reduce search coroutine compilation size and
  remove the identified main-thread settings disk read. Preserve authorization,
  pinned revisions, cancellation, write receipts and existing branch improvements.

### Follow-up changes and evidence

| Observed failure | Change | Regression evidence |
| --- | --- | --- |
| Saved search pages repeatedly rejected original query/mode/index selectors. | Retain bounded read selectors and accept them only when identical to the saved request; reject changed selectors, unknown fields and write payloads without backend dispatch. | Saved search/read pages retain their original scope, reject changes, and dispatch the backend only once. |
| Explicit `RETRY_FAILED_STAGE` restored the same undispatched budget rejection. | On an explicit ordinary-mode retry, reopen only pending `BUDGET_EXHAUSTED` results proven not dispatched; retain the previous rejection for audit, cumulative dispatch counts, successful receipts and uncertain outcomes. Clear stale attempt-local budget flags on retained results. | Normal resume keeps the refusal; explicit retry reopens exactly the eligible call and never replays completed, invalid or uncertain calls. Current admission still enforces its allowance. |
| A provider can continue requesting tools after finalization disables the catalog. | Allow one corrective response, then stop with `FINAL_RESPONSE_REQUIRED` while retaining completed results. | Runner regressions check bounded rounds, one backend execution, and finalization when a shared result exhausts the budget without increasing the runner dispatch count. |
| ART declined to compile the oversized search coroutine. | Separate search preparation, scanning, blob retrieval, coverage retention and response construction; reuse one related-path list per page. | Compile the actual repository session; traverse multi-page path/content searches, cached continuations, truncated trees and bounded match previews. Device ART compilation remains to be verified. |
| StrictMode reported the advanced-settings preference read on the main thread. | Load preferences and decode saved settings on `Dispatchers.IO` before initializing the editable dialog. | Source inspection confirms the reported disk read moves off the main thread; device StrictMode verification remains outstanding. |
| The model guessed nonexistent workflow/package paths. | Suggest at most 12 actual files from the pinned tree, preferring a matching basename across packages, without silently substituting a path. | Candidate ordering, nonexistent paths and hard result bounds. |
| PR #766's starting head failed its existing ordinary-schema token test. | Shorten repeated descriptions without changing schemas, tool capabilities or the test threshold. | Existing regression passes: 2,942 UTF-8 bytes, 981 estimated tokens, three facade definitions; limit remains below 1,000. |

The supplied log also includes rejected watch IDs, two provider tool-markup parse
failures, a recovered transport interruption and platform/inference warnings.
Argument validation and native parser rejection remain in place. These external
conditions and device jank are not claimed resolved by source or host tests.

### Follow-up validation

- `bash scripts/validate-local.sh fast`: 269 host tests and repository checks passed.
- ktlint 1.3.1: all changed Kotlin files formatted and checked.
- 22 focused JVM tests passed using production search/session, facade, result-view,
  checkpoint and schema-estimator sources with inert platform fixtures. This includes
  the existing schema-size regression that failed on the starting PR head.
- Full Android compilation, runner integration, lint and emulator checks must be
  evaluated in CI at the new head; results from the starting head are not evidence
  for this follow-up. The local JDK/SDK limitation recorded above still applies.
