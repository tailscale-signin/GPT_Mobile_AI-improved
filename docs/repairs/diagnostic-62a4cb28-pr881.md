# Diagnostic capture 62a4cb28: PR #881 repairs

## Accepted scope

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
- Target PR: #881, `verify/session-reliability-77c55cc-2026-11-12`.
- Starting target revision: `641a5750544a45935ce7fdeb0c1037fdc50955f9`.
- Base: `main`, observed at `97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`.
- User instruction: add the diagnosed fixes and solutions to the #881 branch.
- Intent: restore new engineering admission without changing retained authority; make status completion and diagnostic evidence truthful; retain bounded gateway operation identity and recover without repeating an uncertain job mutation.

## Evidence and qualification

Capture `62a4cb28-f9cc-4433-a402-86cf78da3a9e` contains app 0.9.66.7 build 141 debug at `342b48d9e9559fb89e06dbd0b2a3f835eae802ec`, selected `main` at the base above. All 20 section hashes and 8,059 JSONL rows validate; 38 chunks reconstruct three complete records. The nine selected-chat runs contain six failures, two input blocks and one status completion. App-wide logs are broader than the selected-chat database rows. All 117 critical log rows duplicate recent log rows.

The installed app is the #838 merge candidate; its tree matches #838 head `9568c8915de6d11726af860fc8ca8d21649220bd`, whose Android production code matches this PR's starting revision. The running gateway advertises a different manifest digest from the inspected packages. Source repairs therefore require deployment qualification against the actual gateway; this document does not claim the private host or phone was updated.

## Repair register

| Area | Evidence | Required behavior |
| --- | --- | --- |
| New implementation scope | Default `taskScope=implement` is omitted by JSON serialization; gateway requires explicit implementation for branch=base. Python validator reproduces rejection. | Send explicit new scope, preserve legacy accepted replay identity, keep pinned-source and separate-working-branch guards. |
| Gateway rejection detail | Five app-wide preflight 409s; reason absent from retained terminal records. | Preserve allowlisted reason, phase, HTTP status, field names and attempt identity through durable/export/report boundaries. |
| Binding transport | Three binding POST EOFs; two in selected chat. | Correlate prerequisite POSTs, distinguish their unknown outcome from the dependent job being unsent, honor stale-connection observations, and recheck the retained selection after connection repair. |
| Model preparation | A prepare EOF becomes vague INCOMPLETE after about 66 seconds. | Keep operation identity, loading state and exact blocked reason; reconcile existing operation and bound polling. |
| Status acceptance | Four accepted requirements replaced by five generic observed rows, all PASS. | Retain accepted criteria, source receipts and partial outcomes until requested coverage is supported. |
| Check pagination | 100 of 132 checks retained; page 2 outstanding; output says 132 observed. | Traverse authorized continuation or report observed/total/completeness separately. |
| Evidence projection | 256-fact DFS consumes nested commit metadata before root identity. | Preserve bounded identity and compact summaries; render the five already-read recent commits. |
| Review input | Main has no selected review baseline; valid input block becomes provider failure. | Prompt for exact baseline and retain typed input-required state without a provider-error incident. |
| Early run journal | All six early failures have zero journal events despite enabled capture. | Register the run privacy scope before gateway admission so typed failures and terminal events are retained. Do not preempt the actual direct/contextual/preparation classification. |
| Manual reports | #884 has GATEWAY/NO_CAPTURE; reports #884–886 were delivered. | Capture safe fresh admission/model metadata before a job exists; preserve report approval bytes and link delivered issue identity. |
| Report schema | ENGINEERING reasons and invalid fields are dropped by app and hosted allowlists. | Versioned end-to-end schema with old approved payloads unchanged. |
| Queue compilation | ART rejects a collector with 18,932 compiler instructions. | Split collector stages without changing ownership, permission, cancellation or replay behavior. |
| Runtime performance | 130 jank frames of 2,606 sampled; six >100 ms stalls, allocation pressure. | Split the oversized queue collector, reduce status evidence projection work, and qualify device traces. General capability polling and GC pressure still need a measured workload before tuning. |
| Integration prerequisites | Later issues #887–889 retain missing fixture/run/job evidence with budget remaining. | Present exact prerequisite and resume only after material evidence/authority changes; retain independent reads and cleanup reserve. |
| Private services | MCP :8101 refuses four connects; another endpoint is configuration-paused. | Repair actual listener/configuration, preserve existing backoff and explicit reprobe; no inferred app-side authorization. |

## Implemented behavior

### Engineering admission and retained authority

New requests explicitly serialize `task_scope`, including its implementation default. A shared JSON contract covers new implementation requests, read/plan requests and historical omitted-scope requests. The host compatibility rule is restricted to the historical implementation default on an already-retained request. It preserves the original accepted bytes, digest, job identity and authority; changing read/plan scope is still rejected. Missing scope and missing/invalid pinned source revisions now have separate fixed reasons.

A disconnected binding or preflight POST is not reported as a successful read or as proof that the prerequisite itself never reached the server. Its downstream engineering job is still unsent. This patch deliberately adds no blanket POST retry: the binding identity includes live configuration and model revisions, so deterministic identity alone does not prove that a later re-prepare preserves the original authority after a host change. The existing read/preflight recovery remains bounded, and durable job mutations retain their no-replay boundary.

Every captured gateway exchange now has an operation and incident identity. Rechecks of an existing model load use a digest of its original operation ID, while physical attempts keep distinct spans. Terminal admission logs link to that identity without promoting it into a model invocation or job identity.

Model preparation retains its original load ID, a bounded transport category, the latest selected-model observation and one of four fixed outcomes: incomplete, rejected, storage unavailable or connection failed. A fresh dependent request remains blocked and unsent when this is explicitly known. Partial output and earlier unknown remote outcomes are preserved. Rechecking an uncertain load keeps the original durable ID.

### Evidence and report integrity

Engineering guard details now survive Android parsing, durable failures, minimal receipts, compact export, report preview and hosted validation. Context version 6 and gateway snapshot version 2 add admission/model observations before a job exists, including manifest hash, version/protocol, known phase, actual HTTP status where observed, readiness reason and a separate hashed preparation operation ID. Earlier approved context versions 1–5 retain their historical serialized contents.

An exact review-baseline prerequisite remains `REVIEW_INPUT_REQUIRED` with an explicit baseline-selection action. It does not create a generic provider incident or automatic `CHAT_FAILED` report. The original capture already proves report delivery for #884–886; this patch addresses evidence quality and classification, not an invented reporting outage.

Early run privacy registration occurs before gateway admission. Typed setup receipts preserve completed rejection transport, reconciled acceptance/replay facts and the actual partial-output scope. They do not replace an earlier uncertain generation with a later precheck error. Temporary/private runs remain excluded.

### Status verification

The status workflow retains the original accepted criteria and criterion IDs. It associates each assessment with captured receipt IDs, observation timestamps and original result hashes; an unknown retained criterion stays blocked. Health requests additionally need branch protection/rules, bounded recent failures and current head/base-matching PR review evidence.

Check inventory distinguishes actual captured rows from reported upstream totals. Completion requires fresh, trusted, matching-SHA, contiguous pages with consistent totals and distinct identities. A refreshed first page invalidates older trailing pages. Planning and assessment use only receipts for the accepted repository with the target role; authorized source repositories and same-SHA forks remain separate. One consistent page size and complete nonterminal PR pages are required for each traversal. Older refreshed PR observations remain in original receipts but are excluded from the current table. The planner uses remaining accepted calls for exact-SHA continuations and never raises the existing six-call scope ceiling; insufficient evidence or allowance produces a partial result.

Bounded breadth-first fact projection keeps the root scope and continuation fields from being displaced by nested REST metadata. Compact tool responses leave the full original receipt and hash intact. The status presentation gives PR head/base roles, recent commit summaries and captured/total check counts explicitly; skipped checks do not become executed tests or merge approval.

### Queue and integration workflow

The durable queue collector delegates to separate serial snapshot, eligibility and persist/dispatch methods. It retains first-prompt-per-chat ordering, cancellation propagation, lease reconciliation, model/preparation guards, provider and spend checks, persistence before dispatch, and per-prompt failure retention. Independent source review found no added concurrency or duplicate dispatch path. The change targets the observed ART oversized-method warning; actual device compilation, frame timing and memory improvement remain unmeasured.

Integration reports render every unresolved capability in grouped prerequisites before the Continue instruction. They distinguish consent, an owned fixture, exact-revision completed run/job evidence, and failed-run evidence from a true tool-budget limit. Remaining budget stays visible. Shortened operation histories, failed receipt histories and receipt ID lists declare their omitted counts. Existing fixture authority, cleanup reserve and material-change resume rules remain enforced.

## Operational solutions and acceptance

1. **Use a matched app and gateway package.** Deploy the complete reviewed package using [the upgrade guide](../../gateway/V14_UPGRADE.md), preserve the original configuration and job database, restart the intended gateway launcher, and compare the observed manifest identity with the package manifest. A shared `16.0.0` label is not source equivalence. Do not regenerate a downloaded package's manifest merely to bypass an integrity failure.
2. **Qualify the exact selected model.** Observe its readiness and existing preparation operation on the original gateway/profile. A successful catalogue read does not prove generation readiness. After readiness is qualified, perform one intended request and confirm its retained job/output. A lost preparation response alone does not authorize an unrelated load or inference replay.
3. **Deploy the matching report service.** The hosted reporter must advertise context versions `[1, 2, 3, 4, 5, 6]` before a new version-6 approval can be sent, plus `GATEWAY_MODEL_PREPARATION_V1` and `REVIEW_COMPARISON_V1` when the corresponding preparation fields or review codes require them. Keep existing approved IDs and payloads unchanged. Old reports remain compatible with their original contracts. The code change does not itself deploy the hosted service.
4. **Supply integration prerequisites under the existing consent.** Read the complete grouped ledger. Use the owned fixture and exact run/job specified by the accepted test scope; grant only a missing requested authority. A report with 48 or 204 calls remaining is not evidence of budget exhaustion. An unchanged blocker should remain blocked until evidence or authority changes.
5. **Repair the actual MCP listener/configuration.** On the configured host, verify the process, bound interface and configured port for the endpoint that refused connections. Restore the exact saved connection or credential through its normal configuration flow, then explicitly recheck and discover one bounded tool. Preserve existing backoff and configuration pauses. This patch neither changes the private service nor bypasses its access controls.
6. **Repeat device performance qualification.** On the same phone and workload, check for the queue compiler warning, compare frame samples and UI/text measurement spans, and profile allocations/GC with actual main-thread pause attribution. The capture does not establish a memory leak, OOM or crash. General capability poll volume remains an optimization candidate, not a proven repeated-load bug.

No voice, microphone, TTS, ANR, OOM, HTTP 429 or gateway HTTP 403 failure was established by this capture. Earlier branch work in those areas is preserved.

## Concurrent branch integration

Before publication, the target branch advanced from the starting revision to `ce7af48f8b974a0392e6b3a9995c8122cc258a89`. Its six incoming commits were merged with their ancestry preserved. The complementary [open-issue repair register](pr881-open-issues-2026-11-12.md) covers explicit review-baseline resolution, exact sleeping-model wakeup, durable preparation uncertainty and observed workflow-job prerequisites.

The overlapping reporting changes preserve both admission and detailed model-preparation captures, all required hosted feature negotiation and immutable approved bytes. A valid baseline-selection prerequisite remains an input action rather than a new automatic incident. A newly reproduced self-test scope defect was also corrected: authorized source-repository status and job receipts cannot replace the selected workspace's dependency observations. Both regressions failed before this correction.

## Verification

Focused results are tied to current source files and recorded in the session's source/dependency manifests. Host harnesses use explicit Android/service boundaries; they do not replace full Android compilation, Room tests, device execution or private-service qualification. Initial harness classpath/obsolete-facade failures were repaired in the harness and are not counted as passes. Skipped, pending, absent and canceled checks are not passes.

| Check | Result |
| --- | --- |
| Gateway scope, durable replay, HTTP and rejection regressions | 95 integrated engineering tests and 4 rejection tests passed on merged source; old normalizer fails the new replay regression |
| Merged integration, baseline, prerequisite report/planner and self-test regressions | 87 host JUnit tests passed; both new source-scope regressions fail before the correction |
| Report context, submission, outbox and admission snapshots | 78 host JUnit tests passed on merged source |
| Durable failure, receipt and compact summary behavior | 76 host JUnit tests passed on merged source |
| Kotlin-approved payloads through the Python service | 22 exact payloads accepted; 21 pre-merge approvals re-emitted byte-for-byte unchanged |
| Hosted report HTTP/configuration/contract tests | 58 passed on merged source |
| Gateway transport, model preparation, readiness and recovery | 76 host JUnit tests passed on merged source |
| Gateway model preparation and HTTP/SQLite behavior | 56 Python tests passed on merged source |
| Status acceptance/pagination/projection | 25 host JUnit tests passed, including 17 new correctness regressions |
| Coordinator setup receipt boundaries | 3 host JUnit tests passed |
| Current engineering client and provider-exception integration | 44 host JUnit tests passed |
| Canonical `bash scripts/validate-local.sh fast` | Passed: 495 script tests, dependency/schema/secret checks, regex/resource/icon checks and diff whitespace |
| Generated architecture and app-audit inventories | Regenerated after merge; app inventory covers 2,522 files (source inventory, not executed-line coverage) |
| Changed Kotlin formatting and gateway source manifest | Pinned ktlint passed on 64 changed Kotlin files; all 220 gateway package hashes match both the worktree and immutable source commit `40f061b1a588bc8ae6d3bfb4bd6029e5d381d963` |
| Full Android/CI and device qualification | Full Android build pending hosted CI; physical device/private services not exercised. Local Gradle download/Android SDK unavailable |

