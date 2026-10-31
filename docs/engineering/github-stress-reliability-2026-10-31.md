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

### Explicit retry provenance checkpoint

- Same repository, branch and base as above; follow-up starting head:
  `8c2b00b7824a90a495dcaa24ff39f9760e0ac5a5`.
- Review found that automatic provider research resumes also construct a
  `RETRY_FAILED_STAGE` descriptor. Intent: persist an opt-in budget-retry flag only
  for user retry controls, default old/automatic descriptors to false, and carry
  that decision through recovery resolution. Text retry commands are explicit;
  continue/writer-only actions do not reopen budget refusals.

### GitHub chat interaction enhancement checkpoint

- User-requested scope added while the follow-up was in progress: continuous
  visible work, progress at least every ten seconds and at major milestones,
  replacement of progress with the final answer, frequent consequential design
  decisions through themed buttons, and decision notifications outside the active
  GitHub conversation.
- Implementation intent: use real execution events plus a bounded heartbeat;
  keep public progress distinct from reasoning and final prose; wait for explicit
  choices without default selection; persist decisions across interruption;
  preserve independent native write approval and cancellation/resource controls.
- Repository/base/branch remain unchanged; implementation starts from `8c2b00b`
  plus the explicit-retry provenance changes recorded above.

### Actual runtime and scheduling review (Phases 5 and 7)

The requested target remains `fix/github-stress-reliability-2026-10-31`.
References in the supplied notes to `test/benchmark-stress-20261031`/`3e0dde43`
are historical evidence, not a request to overwrite that branch. The described
`ai/GPTMobileAI.kt`, `workers/GenieX.kt`, and CoreAgent/MemoryAgent definitions are
absent from this target; AGENTS.md is engineering guidance. Its dependencies are
already newer than the proposed replacements. No purported percentage, coverage,
latency, or memory baseline from those notes is treated as a measurement.

| Requested area | Actual implementation and disposition |
| --- | --- |
| Async loading and streaming | `LocalRuntimeImpl`, `LocalEngineHolder`, `GgufRuntimeAdapter`, and the separate `src/geniex/.../GenieXRuntimeAdapter`; serialized native ownership, asynchronous loading, bounded event queues and context/output admission already exist. |
| Worker fairness and timeout | `FairNativeLock` has root rotation, bounded visible bursts, aging and cancellable handoff. `LocalEngineHolder` limits its queue to 128 and admission to 180 seconds. Keep this scheduler rather than introduce a busy-spin worker pool. |
| Outer admission saturation | `InferenceAdmission` could accumulate unbounded suspended requests while a benchmark holds resources. Now cap blocked waiting at 256, fail before dispatch, permit independent/nested work that can enter immediately, and free capacity on cancellation. |
| GenieX lifecycle | Release a newly created handle if cancellation arrives during blocking creation or capability validation fails; publish only a validated active handle. Destroy an idle owned handle even if stop reports failure, retain original/suppressed errors, clear failed conversation construction, and clean media in non-cancellable IO. |
| Fresh GitHub context and caching | Pinned repository session/cache identities, saved-view scope validation, bounded request joining, fairness and mutation journaling remain in use. Mutable state is refreshed before writes; unknown mutation outcomes must be reconciled, not blindly retried. |
| Build size and dependencies | Existing ABI splits, ARM64 GenieX isolation, R8/resource shrinking and the version catalog remain authoritative. Do not downgrade current coroutines/Room or remove emulator support to claim an unmeasured APK reduction. |
| Benchmarking | Existing `BenchmarkPerformance`, resource recorder, native segment receipts and comparison-identity gates measure cold/warm starts, prefill/decode, first text, cleanup, PSS and thermal state. Device targets in the request remain acceptance goals, not achieved results. |

### GitHub interaction implementation and checks

- A provider-independent five-second heartbeat and immediate tool milestones show
  public activity. The model is prompted to report findings and ask consequential
  design questions throughout feature development. Resource/permission limits and
  explicit Stop remain effective.
- Each model round starts fresh answer prose; previous prose becomes bounded
  progress. Successful final publication removes progress/status entries while
  retaining tool receipts. Interrupted/failed work retains its partial evidence.
- `github_decide` exposes 2–4 themed button choices, an optional recommendation,
  a custom answer and Stop. No choice is selected automatically. A question batch
  cannot race repository writes. Its checkpoint remains queued during human input,
  so interruption cannot classify a question as an uncertain external mutation.
- Questions and answers are scoped to chat, original user message, profile and
  workspace. Saved chats persist them through the existing protected state store;
  temporary chats do not. Temporary-mode changes remove durable copies; deleting
  a conversation removes questions and cancels their waiters.
- Prompts outside the active GitHub chat produce private, conversation-linked
  Android notifications when response notifications and OS permission allow them.
  Design choices and native write approval remain separate.
- Explicit budget retry now carries user provenance. Automatic/legacy recovery
  descriptors cannot reopen an undispatched budget refusal by accident.
- Local validation: 269 host tests; 55 focused JVM tests covering existing search,
  pagination, retry/schema contracts plus decision persistence/replay/revocation,
  native handle cleanup, starvation/handoff and resource admission. Compose,
  runner integration, final-presentation tests and both Android runtime variants
  require the exact-head CI run. Hardware benchmarks remain outstanding.

### Additional build, memory, accounting and modularity scope

The user's next request adds offline/cached model provisioning, configuration-cache
verification, version-catalog consistency/update automation, lifecycle memory
release, explicit hybrid routing, batch/accounting accuracy, bounded semantic
recall/context compression, streaming recovery and a portable domain boundary.
Continue from this checkpoint by inspecting the existing implementations and
closing verified gaps; do not silently change provider destinations or duplicate
existing batch, HNSW, compaction or stream-recovery systems.


### Follow-up checkpoint: portable backups and end-to-end hardening

Repository `tailscale-signin/GPT_Mobile_AI-improved`; base `main` at
`9781b63c72bf3a2a4786ede07f0d9e094cd07db2`; reviewed parent/head
`e49614dc2f3c134641c1bd8c8e9418d727228cd9`. The user expanded this same branch's
scope to offline provisioning, lifecycle/hybrid routing, accounting, batch and
stream recovery, modularity, and complete GitHub backup/restore.

| Area | Implemented or verified behavior |
| --- | --- |
| Build network coupling | Provision pinned USE weights with a standalone bounded/retrying, atomic, hash-checked cache tool. Gradle only validates/copies the local artifact. CI provisions explicitly and checks two-run configuration-cache reuse for packaging and the portable domain. Full Android/ObjectBox cache compatibility is still a separate limitation. |
| Catalog and profiling | Inline library versions moved into the shared catalog; a host/CI guard prevents recurrence. Existing weekly Dependabot remains. Opt-in debug LeakCanary 2.14 uses verified artifacts; release builds exclude it. Existing ProfileInstaller, ABI packaging and R8/resource shrinking remain in place. |
| Lifecycle | Background grace period and idle checks release embedding sessions, bounded vector/query caches and idle model handles. Active inference and explicit idle-off settings remain respected. Encrypted persistent memories can rebuild recall. |
| Hybrid routing | Battery/thermal/low-memory and prompt-complexity policies rank only preauthorized eligible profiles. Explicit selections and private/cloud-fallback choices remain authoritative; routing never adds a provider. |
| Accounting/domain | `core-domain` exposes common Kotlin accounting and routing with a JVM target. Exact overflow-checked integer pricing is checked against a BigInteger oracle; provider counts and estimates keep their provenance, receipt IDs deduplicate, and exact provider/model identities remain distinct. Android/Room adapters retain persistence. |
| Batch | Fixed bounded workers replace sequential pseudo-concurrency. Cancellation closes HTTP calls; response bodies and request counts are bounded. llama-server uses real `/completion` decoded text, not token IDs interpreted as Unicode. Native OpenRouter async batch operations preserve routing/storage policy and do not blindly retry billable submissions. |
| Streaming | Gateway replay verifies job/generation identity and resets sequence cursors across declared generation changes. Transient HTTP status classification is corrected. Partial provider output remains retained; unsupported provider streams are never silently resubmitted as new paid generations. Existing UI streaming buffers remain in use. |
| Memory/context | Existing ObjectBox HNSW, encrypted bounded fact memory, retention policy, semantic recall and sliding context budgets already implement the requested scalable/pruned architecture. This patch improves idle eviction rather than replacing those systems or claiming synthetic speedups. |
| GitHub backups | Encrypted exports include API tokens, OAuth refresh/session/account records and an explicit portable credential marker. Restored OAuth credentials verify account identity; interrupted refresh state stays interrupted. The previously dropped refresh expiration is now persisted. Legacy archives do not silently reconnect. |
| GitHub workspace backups | Settings restore chat workspace/profile/index choices by stable conversation identity, or by the chats restored in the same transaction. Restored write auto-approval and stale observation fields are reset. Tools-only backups include saved repository selections. A separately selectable encrypted section carries file/programmer drafts and their key with rollback-aware file replacement. |
| Backup exclusions | Pending OAuth/PKCE sign-ins, local response caches, execution claims/write approvals, device-only consents, and encryption recovery secrets are not replayed/imported. Existing agent approvals restore as interrupted. Draft restoration never performs a GitHub write. Other existing sections retain conversations, profiles, credentials, memories, research, tools, models, attachments, statistics and health/shopping records. |

Validation before publication: 273 host tests and 63 focused JVM checks pass;
the JVM harness compiles actual selected production files with inert Android/Hilt
fixtures and does not substitute for the full app suite. New Android tests cover
encrypted GitHub API/draft/OAuth round trips, account revalidation, partial restore
identity, response closure/cancellation, delegation, batch payloads and streaming
replay. Exact final-head Android CI must be reported independently. Device startup,
P99 inference, memory, throughput and coverage targets in the pasted proposal are
not measured baselines or demonstrated percentage improvements.

Parent-head Android verification completed compilation and lint. The full unit
run executed 5,638 tests (5,632 passed, four skipped); two prompt-budget assertions
failed after new workflow guidance enlarged the frozen GitHub context. Guidance
was compacted, and commit excerpts bounded to 120 characters, retaining the
existing <2,000-character snapshot contract. Final-head checks cover this repair.


### GitHub profile, long-job and Llama review checkpoint

Repository/base/working branch remain as recorded above. Parent head for this
follow-up is `500bb03ecbee31871e27f92ae8c7b461275777bd`; the existing draft is
PR #766. New user scope: independent GitHub profile overrides, maximum engineering
and context allowance, actionable continuation questions, mandatory Llama review,
and an elapsed-time header with text three points larger.

- GitHub profile preferences default to web search, repository tools, maximum
  context expansion and Ultra Maximum (512 tool calls, up to 24 hours and
  2,097,152 accounted run tokens). These are request-local settings; provider
  capacity, explicit Stop, spend limits, data-sharing grants and write approval
  remain authoritative. Context targets up to 1,048,576 tokens with enlarged
  bounded replay and retained evidence, constrained by advertised provider limits.
- The themed GitHub settings card controls these preferences separately for each
  selected AI profile. Backup/restore now includes them and the global default
  GitHub profile, filtering missing profiles during partial restores.
- Consequential choices still use durable `github_decide` buttons/custom answers;
  final continuation questions use the existing clickable response-action parser.
  GitHub suggestions remain available independently of ordinary-chat preferences.
- For Llama gateway GitHub sessions, no draft answer text publishes before a
  separate tools-disabled review. The runner records receipts before compaction,
  including restored/error results. Every evidence fragment must be acknowledged
  in a strict review verdict. Large evidence uses bounded context windows and a
  final consistency review; capacity overflow fails visibly rather than dropping
  results. One correction is allowed and must pass fresh review. Review requests
  reuse the authorized provider and invocation ledger; tool writes never replay.
- The pinned header aligns elapsed HH:MM:SS at the right with text enlarged by
  3 sp. Saved task timestamps include retries and waiting and survive recreation;
  completion freezes the duration. Ordinary chat typography stays unchanged.
- CI caught and repaired the remaining `URI` import after Gradle network extraction
  and missing Kotlin Multiplatform scripting compiler pins. The eight compiler
  artifacts were fetched from Maven Central and checked against published checksum
  sidecars before adding SHA-256 verification entries.

Local review validation: 76 focused JVM tests passed, including review withholding,
coverage, correction, malformed/failed streams, cancellation, context/evidence
bounds and preservation of original receipts. Android runner/resolver/profile,
backup and UI integration still require the new exact-head CI result.


Final review hardening starts from `52e32fc782cc4211d90d9db1f1f61d7afb6f2ff2`
on the same repository/base/branch. Preserve generated-character/repetition guards
inside the private Llama draft stream, stop an oversized stream immediately,
and avoid splitting UTF-16 surrogate pairs when partitioning review evidence.
Additional behavioral regressions cover bounded source collection, exact Unicode
reconstruction and actual gateway capacity. Review windows use the primary request
admission receipt before partitioning evidence. All 79 focused JVM tests pass.

CI revealed manual Android setup jobs that did not provision the model after
Gradle network extraction. All Android workflows and the local full-validation
entry point now provision explicitly before Gradle; verified cache hits perform
no network I/O. The workflow policy rejects missing or late provisioning on
manual SDK and physical-device jobs, including cross-job false positives.
The host validation suite now contains 276 tests.

Review follow-up from `d52e8a16004e004e9a90e0e4f66250766176fee8`: the shared
tool-budget layer also compacts observations, before the runner sees them. A
run-scoped receipt observer now captures validated execution results ahead of
that layer; the runner still supplies restored and admission-failure receipts.
Regression tests send a 110 KiB result through the real shared budget and runner
and assert the final finding remains available for private review. Ordinary
chats retain their existing bounded delivery without a review recorder.

Focused JaCoCo measurement (selected actual production sources with inert host
fixtures, not whole-app coverage): review gate 94.4% lines, decision persistence
100%, GitHub backup preferences 100%, inference admission 83.0%, fair native lock
93.1%, native handle lifecycle 88.5%, bounded batch workers 100%. Full Android
coverage and device performance remain separate measurements.

The GenieX CI variant compiled production code but rejected the HTTP cancellation
test double after OkHttp added event-listener and typed-tag members. The test now
delegates unused interface members to a real, undispatched Call while controlling
headers, cancellation and body callbacks explicitly. No test request reaches the
network. This is checked against the repository's pinned OkHttp 5.5.0 bytes.

The receipt observer is scoped to the exact executing call, including its name
and arguments. Nested delegated operations can have different data permissions;
only their authorized outer handoff enters the parent's review. An integration
regression verifies that private child results are not observed by the parent.

At `99d6046f78573b49493e51e37c867a6a75529041`, GenieX compiled and ran 5,679
unit tests: 5,674 passed, four skipped, one failed. The failure exposed a changed
local batch error contract after bounded concurrency: raw endpoint exceptions
replaced the local-only recovery message. The local adapter now preserves that
message for callback and JSONL jobs while still propagating cancellation, with
a regression ensuring private endpoint text is never included in the output.

Before publishing the batch repair, the remote branch advanced independently to
`bcbe7b7f5e7f93847d3eba6cefb6fe2cb239dadd` (durable gateway engineering and
intelligent read batches). That commit is preserved in full; the batch repair
is applied as its descendant. Base `main` remains unchanged.

Combined-head validation at `1f1b779a342896f27b78513db1329498b4a50309`
compiled both Android variants and ran 5,693 GenieX tests: 5,687 passed,
four skipped and two failed. Preserve the incoming intelligent whole-round DAG,
but restore the legacy scheduler's bounded chunk boundary so cancellation cannot
claim a later chunk before dispatch. A new regression also requires a cancelled
intelligent worker to terminate its collector immediately rather than leave it
waiting for a receipt. Hoist gateway job state into the workspace screen; the
pinned progress component must render without constructing its own Hilt owner.
Cover the plain header and a completed 25-hour gateway job, and baseline-align
the gateway clock with its bounded status text. Repository/base/branch and PR
#766 remain unchanged for this follow-up.
