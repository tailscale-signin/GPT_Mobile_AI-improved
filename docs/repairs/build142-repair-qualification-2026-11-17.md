# Build 142 repair and qualification

Repository: tailscale-signin/GPT_Mobile_AI-improved. Base: main at
6f0d7ac37b03e63fec04a9c8dc1990768b8549e6. Existing PR: #1534.
Working branch: fix/post-1349-context-pagination-recovery-2026-11-17.
Intake head: aca3a6b6011a77571ba87f29abd80cb051b0a84a.
Intent: implement the November 17 build-142 audit on this branch only.

## Changes

- Normalize safe source reads before native budget/schema admission as well as operation dispatch. Preserve call ID and the original provider invocation in restored journals. Authorization sees the normalized request and still runs before dispatch.
- Accept the declared response_detail option when converting a top-level source path to ranges; normalize the bounded nested EOF form. First-page searches remove only the known unsupported per_page/response_detail options, followed by complete schema, operation and replay-safety validation. Cursor-bearing calls, unknown fields, unsafe paths and mutations are not repaired.
- Apply the existing verification/cleanup reserve to ordinary provider acquisition, retaining a partial task and its ledger before the hard call ceiling. Specialized fixture reconciliation and approval paths retain their earlier ownership.
- Retain bounded receipt-linked exact source SHAs and evidence epochs with acceptance checkpoints. Unknown revisions remain NOT_RECORDED; observed source reads do not claim test success.
- Preserve observed model invocation identity at the generic error boundary without overwriting tool causality. Public diagnostics continue using hashes and explicit unknowns.
- Enrich a native rejected issue-list page with the missing exact-cursor contract and structured validation evidence. Successful REST pagination is unaffected; no cursor or page is silently invented.
- Honor a confirmed upstream shared quota only across models pinned to that provider. Unknown 429s remain route-specific, and unrelated provider routes remain available. Regression covers the observed 60,117 ms wait and restoration.
- Format changed Kotlin files and regenerate both source inventories.

## Issue disposition

The 55 open reports through #1560 remain evidence, not proof that this candidate passed on a device. Families covered by these source repairs: INVALID_ARGUMENTS (#1516, #1519, #1529–1531, #1540–1543, #1549, #1553–1558), budget/acceptance (#1511, #1514, #1560), provider limits (#1509, #1535, #1537, #1544, #1552), generic failure diagnostics (#1507, #1548, #1551, #1559). Existing singleton-pagination, saved-view storage, repeated-admission diagnostics and malformed-frame repairs require their own qualification. Do not close a reproducible incident because a unit test was added.

## Validation and remaining gates

- Local Kotlin formatting and whitespace checks: run on changed files.
- Provider scope/retry host regressions: run actual production policy classes with lightweight request-config/routing ports; not Android integration.
- Canonical fast host validation: run after regenerating inventories.
- Android Gradle tests: local wrapper distribution download is blocked by the environment network. GitHub CI must compile/run the full Android suites at the resulting commit.
- Device/Gateway acceptance: NOT_RUN here. No attached Android runtime, Windows Gateway endpoint, selected model credential or live engineering job is available in this environment. Do not infer Android-to-Windows execution from connector GitHub calls.
- Required before merge/issue qualification: final-head CI, singleton and real collection traversal, exact build-142 arguments through normal/native/restored paths, saved-view reopening after restart, provider wait/resume, exact-SHA acceptance and fixture cleanup, installed summary/export UI, Windows installation and release checks.

Keep PR #1534 draft and unmerged until those gates pass. This document does not authorize relaxed acceptance or automatic issue closure.

## November 17 follow-up on the 55 reports

The latest build-142 archive contains one run with 101 tool events, 95 completed
and six failed, and 18 model invocations. The six failures were four top-level
source reads, one first-page search and one issue-list request missing the
issued cursor. The terminal record has zero of one criteria verified. The
archive records no Gateway job identifier for this run.

| Reports | Observation and candidate response |
| --- | --- |
| #1505, #1541 | Saved views. #1505 records a missing persistent body. The store has a regression for rewriting missing bodies. #1541 supplied a local view cursor. Its report does not identify the rejected handle. Keep local-view recovery distinct from remote issue pagination. |
| #1506, #1510, #1512, #1513, #1518, #1521, #1522, #1525, #1526, #1528 | Single-resource commit, branch-dependency and PR context results were treated as collections. Check singleton responses and real nested collections independently. |
| #1527 | The recovery inventory actually pages through retained evidence handles. Its rows lacked the stable IDs expected by the shared pagination validator. Each page now supplies its handle IDs and total. The selected recovery contract accepts the next page. An expired body still counts as a listed handle with available=false. |
| #1529, #1530, #1553–#1556 | Source-read arguments arrived outside the ranges array. Normalize safe paths before admission and preserve corrected arguments on the receipt. |
| #1516, #1519 | Both operation and argument-shape hashes identify a check watch with commit_sha and wait_timeout_seconds. It lacked a PR number or run ID. The registered schema advertises either selector, supports saved views and directs the caller to discovery. |
| #1549 | A source range had an undeclared nested field. The reporter now identifies the nested range constraint at schema rejection and includes the offending name only if it is a public contract field. The original private key and values remain unknown. |
| #1531, #1542, #1543, #1557 | First-page searches included unsupported presentation or page-size fields. Repair the first page only, preserving later-page cursor requirements. |
| #1540, #1558 | Issue discovery rejected a numbered page. The compact list contract now accepts the issued after cursor. The GraphQL validator distinguishes missing and invalid continuations before dispatch. Reports carry the number of missing or invalid fields and constraint class without the cursor value. |
| #1511, #1546, #1547 | #1511 reached the call allowance. #1546 and #1547 reached the separate result-byte allowance. A one- or two-call ordinary task keeps its usable allowance. Larger source batches retain verification capacity. |
| #1517, #1520, #1550 | The first two tool calls had a failed dependency. #1550 hit the repeat limit. Keep those guards active and retain earlier coded warnings during high-volume runs so the next report can show the preceding events. |
| #1507, #1559 | The original error hash matches OUTPUT_CONTINUATION_UNAVAILABLE. Approved reports now name this outcome directly. |
| #1548, #1551 | The original error hash matches GITHUB_RUN_INTERRUPTED. The reporter now distinguishes a stream ending without a terminal result from an unavailable saved-answer continuation. |
| #1509, #1535, #1537, #1544, #1552 | Provider rate limits. Scope cooldowns to the observed model/route or confirmed shared upstream quota. A provider can still return 429. |
| #1532, #1533 | Malformed provider tool frames. Retain the closed frame subtype without the original provider payload. |
| #1508, #1515, #1536 | Branch policy observation was incomplete. A protected branch and a protection-endpoint 404 can coexist. These reports cannot establish the missing policy. |
| #1514, #1560 | Acceptance evidence was insufficient. #1560 specifically records mutable evidence expiry or revision change. Verify against the identified revision without treating source reads as test results. |
| #1523, #1539 | Integration reports record failed or missing prerequisites. A blocked mutation remains unverified until the prerequisite and fixture can be exercised. |
| #1524, #1545 | Claim extraction timed out or returned an invalid response. Preserve those separate reasons and the preceding bounded event history. |
| #1538 | Reasoning Off was explicitly requested on a route without a verified control after four probes. Keep the explicit setting and report the admission evidence. |

The incident history uses its existing 60-second window and 16-event limit.
During heavy traffic it preserves recent distinct coded warnings/errors and
recent activity in chronological order. These are neighboring observations.
Causal attribution still uses the invocation, receipt or job evidence.

The previous Android suite reported seven failures. The candidate includes
regressions for short durable allowances, strict finite range conflicts, bounded
EOF, the selected issue list, structured cursor rejection, and reporter
provenance. This record describes the candidate and its intake. Final-head CI,
hardware and live service observations must be recorded separately.


## Late November 17 build-142 continuation — unresolved issue cohort

The next installed Android 0.9.66.8 build-142 archive contains 45 tool-event rows
(43 completed and two rejected GitHub search calls), 49 model-invocation rows
and no source mutation or commit operation. The terminal run is
EVIDENCE_MISSING / GITHUB_EVIDENCE_REQUIRED; it has no retained tested SHA.
These are observations from the installed build, not validation of this PR head.

* **#1562**: both source requests set max_new_blobs=0 (outside the declared
  1..100 range). One uses initial index_cursor=0; the other has no cursor.
  GitHubRecoveryCalls.repairSourceRead now clamps this bounded first-page
  parameter before native admission without changing invocation identity.
  It still rejects opaque/advanced cursors, malformed parameter types,
  extra unknown fields and later-page rewrites. Direct native, provider-recovery,
  validator and cursor-guard regressions were added.
* **#1561**: an observed OpenRouter HTTP 403 reports category PERMISSION and
  remote acceptance NO. This is not a recoverable provider 429.
  IssueReportIncidents.chatFailure now classifies this tightly evidenced
  case as SOURCE_ACCESS_BLOCKED with retryable=false and
  RETRY_AFTER_CONFIGURATION_CHANGE. Unknown acceptance, other HTTP statuses
  and untyped provider text do not get this inference. Actual provider access
  cannot be repaired without verified credentials/routing evidence; the report
  change improves triage without asserting that access now works.
* **#1560** and the terminal archive outcome: the task required code edits and
  tests, but all recorded tools were read/discovery operations.
  ProgressiveToolCatalog previously deferred stage/commit contracts even
  for explicit authorized GitHub engineering goals. GitHub mode now prioritizes
  those contracts in the initial optional schema window when the request
  expressly calls for staging/committing. It does not grant permission, bypass
  wire-context admission, or advertise the larger github_write by default.
  Acceptance remains fail-closed until exact-revision test receipts are observed.
* **#1542, #1543**: earlier per_page search corrections remain in this branch.
  Do not regress first-page correction or allow unknown/advanced continuation
  fields to bypass admission.

The archive also includes locally retained report correlations that did not
appear in the searched open GitHub issues. An unsurfaced local report does not
prove failed remote submission; no duplicate issue was created.

### Qualification checklist for this continuation

- [ ] Current exact-head GitHub Actions unit, lint, security, audit, gateway,
  packaging and required-branch checks
- [ ] Real installed Android build with both max_new_blobs=0 requests
  successfully corrected before dispatch
- [ ] Provider 403 report verified end-to-end without raw identity leakage,
  along with credential/routing recovery on the actual provider
- [ ] Selected-profile GitHub coding task demonstrates dispatched stage and
  commit calls with exact-SHA evidence and passing tests, not just discovery
- [ ] Verify durable saved-view reopening and Android-to-Windows Gateway
  restart/resume with preserved job ownership and no duplicate mutations
- [ ] Windows Gateway installer/release and installed UI/XLSX acceptance

Keep PR #1534 draft and unmerged until relevant tests and live acceptance
gates pass. Source fixes are candidate repairs; an issue should record whether
closure represents verified device recovery or diagnostic enhancement only.
Never mark a provider-access problem as repaired solely because its incident
has a clearer public code.


## Follow-up intake: November 18 UTC, Android build 142 and 28 open reports

Attached archive: `diagnostics-b5d39c3f-1d6d-4c10-bbf0-bbb42dc2fd4f-part-1-02288115-12ed-4bcc-93a1-432dd4a0ffda.zip`.
Its manifest confirms 100% of selected capture stages completed, **not** complete upstream/provider history.
The retained run is PARTIAL / `GITHUB_TASK_INCOMPLETE`, with no tested SHA or Gateway job identifier.
It records 37 GitHub tool results, 36 successful and one failed. The full-catalog check
reported 22 verified capabilities, 54 blocked, 21 not tested and one failed; 63 of
100 permitted calls remained. These are installed build-142 observations, not proof
about the newer PR head.

| Open reports | Confirmed observation | Candidate disposition and proof needed |
| --- | --- | --- |
| #1584 | github_ci workflow runs: six successive live-verified pages, 170 reported results; the terminal page saw only its own 20 records. | Source fix: retain one `verify_live` traversal through pages 2+, beginning a new epoch only at page 1. Six-page regression and contradictory empty-terminal regression committed. Android/live confirmation required. |
| #1585 | 0/1 top-level criteria verified; a failed workflow read, missing prerequisites and pending continuations despite 63 calls remaining. | Resume the capability matrix from receipts, avoid marking blocked or untested capabilities passed, and rerun authorized fixtures against an exact revision. |
| #1582–1583 | Upstream provider shared-pool HTTP 429s with timed retry deadlines. | Candidate has provider-pool cooldown scoping. Verify wait/resume and unrelated-model admission on an installed build; a real quota response is not itself an application defect. |
| #1578, #1581 | Generic `GITHUB_RUN_INTERRUPTED` without causal job identity. | Preserve durable task/job and first-failure source on the next recurrence; do not infer backend job termination from a chat-stream ending. |
| #1566–1570, #1573–1577, #1579–1580 | Twelve `CHAT_FAILED` reports with absent/unrecognized producer code and no bound causal invocation. | Candidate now publishes the observed `AGENT_FAILED_EVENT` capture site and three typed presence flags for code/invocation/details. This is a diagnostic enhancement, **not** a verified recovery. |
| #1571 | github_read rejected undeclared top-level read fields. | Existing strict bounded read normalizer is the candidate; preserve schema rejection for unsupported keys. Reproduce exact original inputs on device. |
| #1572 | github_pr headers requested without `pull_number`. | Keep required selector admission; discover the PR number from the intended repository rather than guessing a PR. Validate the correction path. |
| #1562–1563 | github_search received `max_new_blobs=0` before schema admission. | Candidate clamps bounded first-page search options and preserves original-call evidence; test saved/resumed and later-page paths. |
| #1542–1543 | First-page search passed unknown `per_page` presentation option. | Candidate strips only known first-page presentation fields before strict validation; verify on installed app. |
| #1560, #1564–1565 | Acceptance evidence unavailable, expired or not matched to tested revision. | Observe exact source SHA and successful test receipts separately; preserve NOT_TESTED without matching evidence. |
| #1561 | Provider returned HTTP 403 PERMISSION with remote acceptance NO. | Candidate classifies tightly evidenced rejection as SOURCE_ACCESS_BLOCKED and advises configuration change; client cannot grant provider access. |

Additional independent build failure: Windows Gateway 17.0.1 setup compiles in CI, but
`gateway/desktop/smoke_windows.py` failed at its first clean stop assertion after
`request(root, 'stop')`. The candidate preserves a bounded stop-failure record and
redacted supervisor-log tail for the next CI run. It does not treat a packaged executable
as release-qualified while this smoke check fails.

**Issue closure contract:** A source patch or diagnostic enhancement alone does not
prove installed-device recovery. Close as completed only with exact-head passing
regressions and applicable installed-device evidence; if an upstream or unsupported
condition is intentionally not fixed, record the diagnostic enhancement explicitly,
close as not planned only where justified, and retain the separate acceptance gate.
Do not merge PR #1534 while any required CI or Windows release gate is failing or
device/Gateway acceptance remains unverified.

## Review lanes for PR #1534 (84-file snapshot, November 18)

The requested repairs remain on **one existing branch**. Scope can be reviewed in
independent modules rather than splitting source changes across competing PRs.

| Review lane | Changed files | Focused evidence |
| --- | ---: | --- |
| Android production and UI | 35 | GitHub operation dispatch, pagination, output budgets, source receipts, diagnostics and presentation |
| Android regression tests | 28 | Exact-argument admission, PR-context coverage, provider retry, acceptance, privacy and UI behavior |
| Reporting server | 5 | Issue-contract validation, typed incident fields and privacy-safe submission |
| Windows Gateway | 8 | Installer smoke, release package integrity, gateway contracts and runtime qualification |
| Documentation | 3 | Acceptance evidence, expected-head qualification and architectural change map |
| CI and PR review | 2 | Installer workflow and PR review rules |
| Release metadata | 2 | Version/scope evidence, not runtime proof |
| Release tooling | 1 | Windows package publication safeguards |
| **Total** | **84** | **One PR, independent review and verification gates** |

### PR file-list and empty-review coverage

- A PR context is one selected resource. Its optional `files` field is a
  **30-file page**: for 82 changed files, pages 1, 2, and 3 should carry 30,
  30, and 22 file records. Validate `files_returned`, `files_total`, and
  `has_more` against the requested page before accepting the result. If
  requested files are absent or fewer than expected, report
  `GITHUB_PAGINATION_INCOMPLETE` with
  `MISSING_REMOTE_RECORD_COLLECTION` or `INCOMPLETE_REMOTE_COVERAGE` and
  offer a deliberate first-page `verify_live=true` refresh at the observed head.
  Do not auto-repeat a mutable read against a silently changed revision.
- `files_included=false` intentionally omits the file collection. A successful
  checks-only PR context must not be called an incomplete file traversal.
- `github_pr:reviews` may return an explicitly empty `reviews: []` collection.
  This can establish complete **page coverage** with zero records; it does not
  establish successful tool acceptance. `acceptance_status=NOT_MEASURED`
  is deliberate until matching tests and retained receipts are verified.
- The GitHub connector can independently retrieve all 82 changed filenames.
  The old app's 24,576-byte budget and lost file observations therefore do
  not prove that PR files are absent from GitHub. Validate fresh Android
  behavior with saved-view pagination after the candidate is installed.

Keep PR #1534 draft until exact-head CI, Android/Gateway/installer checks, and
live runtime acceptance are verified. Review modules do not independently
authorize merging or issue closure.

### Native PR review read wiring

The model-facing `github_pr:reviews` translates to `pull_request_reviews`. The workspace client now advertises this read and retrieves the GitHub REST review collection in 30-record pages, preserving stable review IDs, review metadata and exact native continuations. An explicitly empty `reviews: []` result completes its remote page without recording any acceptance pass; the integration regression protects the dispatch mapping. This is separate from PR file pagination and is not evidence that a human review was submitted.


## November 18 archive and open-issue intake (PR #1534 only)

This is a **source intake and diagnostic disposition**, not proof of installed
Android recovery. At inspection there is one open draft PR and 89 open
diagnostic issues, associated with **older build-142 commits**, not with the
current PR head. Do not mark an issue fixed solely because a regression test,
a diagnostic enhancement, or an earlier PR commit exists.

| Failure family | Open reports | Observed cause / required evidence |
| --- | ---: | --- |
| CHAT_FAILED | 17 | Generic error boundaries may lack causal detail; retain invocation and specific prior failure identity |
| INVALID_ARGUMENTS | 15 | Typed read/schema rejections, including an unissued saved-view cursor; never replay a guessed cursor |
| SAVED_VIEW_UNAVAILABLE | 12 | Captured `BODY_MISSING` on saved-view restoration for `github_issue`; requires durable body/reopen checks |
| GITHUB_PAGINATION_INCOMPLETE | 9 | Real incomplete remote traversal; require contiguous evidence for each requested page |
| BUDGET_RESTRICTED | 7 | Result-byte pressure after dispatch; retain receipts and verification reserve |
| GITHUB_EVIDENCE_REQUIRED | 6 | Mutable observations aged beyond 120 seconds; refresh exact selected revision |
| PROVIDER_RATE_LIMITED | 5 | Observed upstream OpenRouter HTTP 429 and provider-shared retry windows |
| GITHUB_RUN_INTERRUPTED | 4 | Retain task checkpoints; distinguish interruption from execution outcome |
| TOOL_FAILURE | 4 | Precheck/execution errors require operation-bound causal receipts |
| TOOL_PROTOCOL_INVALID_FRAME | 3 | Parser may not expose the frame subtype; record a closed recovery-outcome field |
| TOOL_BUDGET_EXHAUSTED | 3 | Observed 24/24 calls consumed; preserve incomplete acceptance |
| GITHUB_TASK_INCOMPLETE | 2 | Verification reserve exhausted with required capabilities untested/blocked |
| NO_VISIBLE_ANSWER | 1 | Terminal delivery yielded no displayable answer; preserve content/state counters |
| OUTPUT_CONTINUATION_UNAVAILABLE | 1 | Missing continuation is not permission to replay completed mutations |

**89 open issues, not 89 verified code regressions.** Representative reports:
#1647 (expired mutable evidence), #1646 (incomplete 98-capability audit),
#1645 (commits pagination), #1644 (provider 429), #1641 (saved body missing),
#1635 (unissued cursor), #1633 (invalid tool frame), #1619 (empty answer),
and #1617 (exhausted call allowance). Preserve distinct issue identities.

The latest `app-diagnostics-7802265199620164715.jsonl` contained **3,417
valid entries** and **59 events flagged for issue reporting**; informational
events and recovered metadata reads are not new confirmed bugs. The two-part
diagnostic archive ended its second part at **85%**, with
`captureCompleted=false`, `continuationAvailable=false`, and
`recoveryAction=NEW_CAPTURE_REQUIRED`. Missing workspace, conversation and
recent-log spans prevent complete-coverage assertions. Captured run outcome
`PARTIAL` / `GITHUB_TASK_INCOMPLETE` /
`VERIFICATION_RESERVE_REACHED` is not successful repair. The captured
98-capability scope records 27 verified, 50 blocked, and 20 untested; it does
not establish full acceptance.

Source inventory after UI changes: 2,668 source files, 434,602
source-associated lines, SHA-256
`d911043288f670d2fa5cad157a9ede8a428a541660eb5b8e93aeeab70e425f35`.
This is *not* executed-line coverage or installed Android qualification.

**Disposition:** keep unresolved issues open until matching exact-head CI,
installed-build reproduction, saved-view and provider retry receipts, complete
issue/CI pagination, and selected-profile Android-to-Gateway live acceptance.
Closing an issue for added future diagnostics must identify the new recorded
field without claiming the original runtime defect is repaired.


### GitHub Copilot Agent Merge · opt-in native integration (November 18)

GitHub remote AI profiles may delegate failing CI checks and merge conflicts
using native `github_pr` operations `agent_merge_status`,
`agent_merge_enable`, `agent_merge_update` and `agent_merge_disable`.
Their fixed GitHub.com endpoint is
`/agents/repos/{owner}/{repo}/pulls/{number}/agent-merge`.
Status performs an authenticated, live GET; enable/update use a GitHub-journaled
PUT with the closed repair permission subset
`fix_ci`, `resolve_merge_conflicts`; disable uses a journaled DELETE.
These are separate from GitHub auto-merge, which is never activated.

The GitHub plugin defaults `githubCopilotEnabled=false` and permits
choosing which repair categories can be enabled. Existing and new
conversations default off; opting in saves a conversation-identity,
repository and profile-bound preference. The app rechecks that consent
through the SettingRepository before each mutating tool dispatch,
enforces the selected workspace and exact current PR head and requires
normal GitHub tool approval. Simply flipping either switch does
**not** enroll a PR, launch Copilot or incur repair credits.
The account must independently qualify for user-authenticated
Copilot subscriptions, repository write permissions and automations.

Affected existing files and new tests belong to this single draft PR;
no separate PR or automatic merge is requested. Exact-head build,
Kotlin formatting, permission regressions, GitHub API/mock tests and
installed Android UI validation are still qualification requirements.

Agent Merge cleanup may explicitly call `agent_merge_disable` even after the local
opt-in is revoked or the PR is closed. Switching a conversation off alone does
not remotely unenroll a PR. `agent_merge_controls_auto_merge=false` describes
the scope of these tools, not the repository's observed separate auto-merge
configuration.

---

## Stacked PR #1741 — post-1534 runtime follow-up

Working branch: `fix/post-1534-runtime-continuation-and-acceptance-2026-11-18`; stacked draft PR #1741 is based on PR #1534, not on `main`.

This document records **source improvements**, not installed-device acceptance. The latest audit observed 160 open issues across 20 error families; the Android build 142 synthetic merge-test commit is relevant to the parent PR.

## Implemented
- An issued saved-view `view_cursor` is the only argument of a local continuation. Full, selected-action and inspect schemas expose a mutually exclusive cursor-only shape, preserving normal action and approval constraints.
- Saved bodies persist their *initial actually issued cursors* as immutable bootstrap proof. If the mutable cursor index is lost, only these exact initial handles can be restored after restart, without a GitHub read or mutation replay. Newer handles remain dependent on the surviving cursor index. A missing body still fails closed and requires fresh authorized source evidence.
- The integration preset now requests prerequisite-first verification for current repository/commit, model capacity, authorized tool catalog, workflow/run inputs and an approved owned fixture before exhaustive operations. Unexecuted capability checks remain `NOT_TESTED`.
- Added regression tests for the six major GitHub read families, saved-view restart recovery and refusal to invent missing legacy cursors.
- Refreshed source-association inventory and generated Kotlin digest for every modified production file. Source association is not executed-line coverage.

## Open issue families (baseline, not reclassified as fixed)

| Family | Open | State |
| --- | ---: | --- |
| INVALID_ARGUMENTS | 44 | Cursor contracts changed; device regression pending |
| CHAT_FAILED | 24 | Retain first typed provider/tool failure; device evidence pending |
| SAVED_VIEW_UNAVAILABLE | 16 | Initial index-loss recovery changed; body-loss eviction remains unresolved |
| GITHUB_EVIDENCE_REQUIRED | 15 | Live exact-SHA test receipt matching pending |
| GITHUB_TASK_INCOMPLETE | 10 | Preflight guidance changed; blocked fixture/workflow readiness still pending |
| GITHUB_PAGINATION_INCOMPLETE | 9 | Existing contiguous traversal needs real provider verification |
| BUDGET_RESTRICTED | 7 | Durable result paging required |
| TOOL_FAILURE | 6 | Precheck lineage verification required |
| GITHUB_RUN_INTERRUPTED | 5 | Durable job checkpoint/resume required |
| PROVIDER_RATE_LIMITED | 5 | Observed provider/model quota scope required |
| CONTEXT_CAPACITY_EXCEEDED | 5 | Observed 4,096 conservative context; don't invent a larger serving limit |
| TOOL_PROTOCOL_INVALID_FRAME | 3 | Parser/recovery tests required |
| TOOL_BUDGET_EXHAUSTED | 3 | Verification and cleanup allowance required |
| RECONCILIATION_REQUIRED | 2 | Unknown writes must never be replayed |
| OUTPUT_CONTINUATION_UNAVAILABLE | 1 | Retain checkpoint; no write replay |
| NO_VISIBLE_ANSWER | 1 | Delivery-layer end-to-end test required |
| SOURCE_UNAVAILABLE | 1 | Verify provider authorization and route |
| GITHUB_VALIDATION_FAILED | 1 | Verify pinned source path |
| CONNECTION_ABORTED | 1 | Preserve partial stream state |
| DISPATCH_POLICY_CHANGED | 1 | Refresh authorization and renewed approval |
| **Total** | **160** | Distinct reports, not necessarily distinct code bugs |

## Release qualification required

- [ ] Exact-head Android JVM, Kotlin lint, emulator and strict inventory CI
- [ ] Installed Android saved-view initial and subsequent-page restart proof
- [ ] Provider context-admission and rate-limit tests on selected remote models
- [ ] Android-to-Windows Gateway MCP readiness, durable job checkpoint/resume
- [ ] Complete permitted capability audit on task-owned disposable fixture, cleanup and matching SHA receipts
- [ ] Individual issue resolution evidence and human review
- [ ] Parent and child merge eligibility, separately verified

Never close an incident solely because diagnostic fields or tests were added. No permission, budget, provider capacity or remote write outcome is inferred.


### Protected saved-view tier — follow-up qualification

The saved-view body and cursor index now have a dedicated encrypted, fsynced, bounded saved-results store (48 MiB / 1,024 records), separate from the ordinary 48 MiB LRU cache and from critical mutation receipts. Ordinary cache pruning cannot silently remove a published saved view. Legacy cache-backed saved views are migrated on their next authorized local read. Exhausting the dedicated tier fails explicitly and does not authorize repeating GitHub mutations. Added a disk-pressure/restart regression that writes more than 48 MiB into ordinary cache after a saved view exists. Installed Android and selected-profile acceptance remain required.

## November 18 continuation: archive capture and unknown-context tool admission (PR #1741)

**Observed in the uploaded old Android build 142:** 76 `ARCHIVE_WRITE_FAILED:IllegalStateException` save receipts, no research-archive membership rows in the captured database, and a terminal `CONTEXT_CAPACITY_EXCEEDED` admission after an estimated 3,802 prompt tokens with unknown context represented by `Int.MAX_VALUE`. These observations are from `ba54824b5e43`, not from a newly installed candidate.

- The archive indexer and durable retry handler now record a *closed* cause code, operation phase, and bounded owned-code location. They never export the raw exception message, arbitrary stack trace, source content, or private URL. Original source rows and receipts remain retained. Owner-deleted and explicitly superseded archive records do not silently replay; other failures back off and require manual review after 16 attempts.
- The unknown-capacity tool-request planner now uses the existing 4,096-token conservative ceiling **before** history and tool-schema selection, with up to 768 output tokens reserved for the initial step. This does not enlarge any advertised, configured, or verified server capacity. Non-optional context that still cannot fit is rejected before dispatch rather than silently truncated.
- Added focused Robolectric/host tests for bounded retries, the closed diagnostic contract, and historical context-budget failure. These are committed test changes, not yet evidence of passing exact-head CI or runtime recovery.
- Required follow-up: inspect actual Room error site on an installed build, test archive membership and restored receipts after process restart, verify context plan with the selected remote profile, exercise GitHub saved-view reopen/cursor fidelity, and qualify Android-to-Windows Gateway resume with live receipts. Keep unresolved issues open until their specific root cause is resolved and independently verified.

Work remains solely on PR #1741, based on main after PR #1534 merged. No other branch or PR was modified by this continuation.

## November 18 review of 102 open issues on PR #1741

The intake contained 101 incident reports and tracker #1746. The table records the implementation or the evidence needed to resolve each report. Counts reflect this intake. A fix in the branch still needs verification on a build containing it.

| Issue family | Open reports at intake | Repair or verification needed |
| --- | --- | --- |
| GitHub argument validation | #1563, #1571, #1572, #1631, #1635, #1653, #1658, #1664, #1668, #1671, #1676, #1705, #1729, #1731, #1732, #1736, #1737, #1752, #1763 | The compact PR tool now advertises the pull number required for headers and context. Recheck the other reported selectors with their owning tools. |
| Required tool definitions | #1757, #1764 | The exclusivity rule now transmits each field’s value constraints once. A profile still needs enough capacity for its required tools and output. |
| Saved results | #1608, #1614, #1628, #1636, #1641, #1651, #1657 | The app can restore a missing body file from the same session’s retained copy. Saved pages also enforce the originating tool alias. A cold start needs a durable copy or an authorized source read. |
| Pagination | #1584, #1602, #1618, #1747, #1748, #1749 | The branch includes terminal-page and saved-page repairs. Verify a multipage inventory and its final coverage on the installed build. |
| Provider failures | #1579, #1580, #1625, #1632, #1683, #1686, #1688, #1730, #1753, #1754, #1759, #1760 | Successful HTTP responses keep their status separately from a logical failure. Confirmed rejected requests retain their HTTP cause. Several reports omit the response or producer identity. |
| Execution budgets | #1591, #1594, #1596, #1599, #1600, #1604, #1605, #1606, #1617, #1755 | Local Combined admission now owns its failure receipt. Tool-call limits and model capacity still apply. Repeat the task within its configured limits to qualify completion. |
| Prompt capacity | #1681, #1716, #1717, #1734, #1740, #1750, #1758 | The branch has bounded request planning. Measure the request, selected model capacity and output reserve on the affected profile. |
| Evidence and acceptance | #1560, #1565, #1585, #1589, #1646, #1647, #1650, #1661, #1674, #1678, #1689, #1707, #1708, #1715, #1718, #1726, #1738, #1739, #1744, #1745, #1756 | Acceptance requires the task’s missing evidence. Run its verification steps using an authorized repository and the selected profile. |
| Continuation and output | #1586, #1619, #1633 | Confirm the saved handoff, stream frame and final visible answer with the originating provider. |
| Tool failures and source validation | #1621, #1623, #1648, #1649, #1656, #1706 | Use the affected source and authorized binding to verify read, search and validation. The original reports alone cannot establish source availability. |
| Dispatch and reconciliation | #1669, #1690, #1694, #1695, #1742 | Reconcile the original operation against its receipts and current policy before any permitted retry. |
| Connection, service limits and gateway | #1702, #1743, #1761 | Verification requires the affected connection, provider quota or running gateway with the requested capability. |

Issue #1760 includes a stack frame at the current-input check in `ContextBudgetService.plan`. The planner now preserves the complete current turn, uses remaining headroom for an automatically selected output reserve when viable, and reports a typed capacity failure with the prompt, safety, output, tool-result reserve and context limit when it cannot fit. Explicit output reservations retain their requested value.

The full and action-specific GitHub source-read schemas now require ranges, a nonblank evidence handle, or an issued saved-view cursor. The cursor remains exclusive. Follow-up report #1765 arrived after the intake. Its status receipt showed eight calls within a 32-call limit, observed branch rules, and a 404 from the classic protection endpoint. The missing required-check policy is a distinct verification prerequisite.

For #1765, an authenticated branch-context read can now corroborate the classic-protection response against a second source. It requires administrative visibility, an explicit null classic rule and the exact repository, ref and head in an error-free GraphQL response. Complete applicable rules still supply the required check names. Missing access, a changed head, a partial rule page or malformed required-check parameters keeps the policy incomplete. Verify on the affected account before closing the report.

The diagnostic fingerprints in #1580 and #1754 match the app's own output-continuation no-progress and provider-invocation failure codes. The Android app and report service now retain these codes in approved reports. The service also accepts the local budget failure code in the provider receipt for #1755. Submission checks the host capability before uploading a report that uses the new values.

Saving a later page after a missing body file now recovers the original capture under the state-store lock before updating its cursor index. A restart after index loss restores only the initially issued cursors.


## Privacy eligibility continuation — November 18, 2026

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`; base `main` at `326683b484f9fea4dfc585fc9732f4b3b97d9068`; existing PR #1741 branch `fix/post-1534-runtime-continuation-and-acceptance-2026-11-18`; observed start head `aacd0c82cfaa43a78b770a5a72a6dce264fdeb75`.
- Intent: automate strict OpenRouter endpoint discovery, separate account-setting evidence from model/route selection, make each blocked/unknown/pending state actionable, and preserve maximum privacy and managed revocation.
- State: SOURCE_IMPLEMENTED; exact-head CI and live acceptance are pending. A catalog entry or `zdr:true` alone does not prove exclusion of Safety Retention, account I/O logging, optional product-improvement collection, default plugins or external Broadcast exports. Current ordinary-key metadata cannot attest those account settings. Account evidence remains a live gate; no user prompt is sent by eligibility checks.
- Initial GitHub evidence: sole open draft PR, no reviews or review threads; strict main rules require `Required verification` and `Analyze (java-kotlin)`. Traditional branch-protection read is unavailable to the integration (HTTP 403); no bypass is permitted. Exact-head checks are incomplete.
- Qualification: focused source tests and canonical fast validation are recorded below. Physical Android, live provider/GitHub, durable Android-to-Windows Gateway, and real Windows installer acceptance remain NOT_RUN release gates.

### Privacy feature implementation and qualification

The same PR now separates explicit account-wide OpenRouter evidence from per-request model/endpoint checks, pins observed concrete models and strict ZDR upstreams, preserves global/profile restrictions, and exposes actionable Checking / Available / Not verified / Incompatible states. Legacy approvals keep their exact scope. Missing account logging, Broadcast, default-plugin or policy evidence still blocks the route. See `docs/privacy-verified-profiles.md` for setup and the current API contract.

Queued dispatch revalidates original permissions, cancellation, signed/managed approval, connection routing and a persisted privacy revision covering custom-term edits and off/on transitions. Authorized guardrail tightening records the exact mutation intent before dispatch; a lost response remains unresolved and cannot automatically replay. The network boundary rechecks current mutation permission and approval before writing bytes. Typed privacy failures retain only closed status/reason codes, preserve unknown acceptance and prohibit automatic replay. Private sessions remain excluded from incident and journal capture.

- Canonical `bash scripts/validate-local.sh fast`: **540 host tests passed**, plus metadata, schema, dependency-policy, secret scan, Android regex and resource checks.
- Focused Android-source JVM harnesses: strict approval/model/routing/wire and dispatch-context contracts **40/40**, profile-selection ViewModel **10/10**, persisted privacy revision **6/6**, guardrail mutation authorization **9/9**, diagnostic projection/submission and runtime classification **8/8** passed. Actual production classes and repository tests ran with framework/network boundaries stubbed; these are not installed Android evidence.
- Hosted reporter and readiness suites: **151 tests passed** on the combined source across incident contract, service and capability checks (150 before incorporating the concurrent repair). The new report values require `PRIVACY_ELIGIBILITY_EVIDENCE_V1`; an older host does not silently accept an unsupported report.
- Pre-push GitHub CI on observed head `aacd0c82cfaa43a78b770a5a72a6dce264fdeb75`: [unit job 135231774391](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/44426220278/job/135231774391) compiled and ran 11,434 tests; three failed and five were skipped. Failures were delegated-tool grant admission in `ChatRepositoryImplTest` and two jump-to-bottom visibility assertions in `ChatBottomIndicatorTest`. Test fixtures are repaired on the same branch: the delegated-tool mock now matches and executes the live authorization callback, and chat indicator tests use a stable overlay host with separate Compose-clock and Android draw settling. Existing denial, visibility and follow assertions remain. The callback mismatch was reproduced by a focused MockK fixture; the UI fixture diagnosis still requires full Android CI. No green qualification is inferred from the passing subset. CodeQL on that old head passed, but a new push requires fresh checks.
- Full local Android test task: **BLOCKED before compilation**, because the offline dependency cache lacks Kotlin Gradle plugin 2.4.20. Required GitHub Android and CodeQL jobs remain authoritative; a host harness is not a substitute.
- Production source inventory regenerated; explicit scope entries cover the new privacy evidence and behavior tests. No release version, release publication or runtime qualification was advanced.

### Open-issue evidence audit at this continuation

At this audit snapshot, all **105 open issues** were read across both observed result pages: 104 incident reports plus canonical live gate [#1746](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1746). The reports have **104 distinct evidence fingerprints**. No exact duplicate group is established, and no report has matching installed/runtime qualification establishing completion. **All remained open at the snapshot.** In particular, #1766 and #1767 are fresh reports from build source `367827bb4c15370bf12b7babf321937bc80457f2`; the privacy implementation does not claim to resolve those GitHub admission/continuation failures. Later issue-state changes are recorded below; this table is immutable evidence, not a live open-issue count.

The fingerprint is SHA-256 of canonical sorted JSON including build SHA, version/device, feature/error, context and nonvolatile diagnostic evidence. Timestamps and invocation/run/call/server correlation hashes are excluded; missing dimensions remain missing. The observed error code identifies a failure family, not a demonstrated root cause. A duplicate requires every comparison dimension to be known and the full evidence fingerprint to match. Completion requires reproduction and acceptance on the repaired source. The neighboring-event list is not promoted to causal evidence.

The table is an immutable audit snapshot taken at 2026-11-19T00:13:35.204569+00:00 (November 18 in America/Toronto). Later reports and PR heads require a fresh read. Every row's disposition is **KEEP OPEN — matching runtime qualification missing**.

| Issue | Operation | Provider | Stage | Observed failure | Build source SHA | Evidence fingerprint |
| --- | --- | --- | --- | --- | --- | --- |
| [#1560](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1560) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | a1c8896fd4e4f711de3df99464dd8ac31dc50e67 | 337614bf5d242bc5c8968102f5c338c2415fb87406fceee853f5581f62fafbb3 |
| [#1563](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1563) | github_search | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | 7b39922cf7c8ed59cba6cc0a83fa2effc5c4efe0 | b0c7365e777015e15dc68ee3b372f5667f258f27645f44b06fd1301cc14fbe17 |
| [#1565](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1565) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | 7b39922cf7c8ed59cba6cc0a83fa2effc5c4efe0 | dfbd4dafafaf9a798519140033b948c1981354c1c0a0b342b792ea17bab42cd8 |
| [#1571](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1571) | github_read | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | 7b39922cf7c8ed59cba6cc0a83fa2effc5c4efe0 | 48df1a3c833329697031b9d9a908cd2b6ce399b2bbbd620e1ae5319f15883659 |
| [#1572](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1572) | github_pr | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | 7b39922cf7c8ed59cba6cc0a83fa2effc5c4efe0 | 9b5d6417e9acecdba44225a21c43138719beed5fcbe0fad52f12af9b01a2b673 |
| [#1579](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1579) | UNAVAILABLE | OPENROUTER | UNAVAILABLE | CHAT_FAILED | 7b39922cf7c8ed59cba6cc0a83fa2effc5c4efe0 | 5956d6c0fc38e7c2f2f36f065c86b48400d5677bdc731278b40cbd51f66b9de3 |
| [#1580](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1580) | UNAVAILABLE | OPENROUTER | UNAVAILABLE | CHAT_FAILED | 7b39922cf7c8ed59cba6cc0a83fa2effc5c4efe0 | ade9560abd2ed8b89cc5a2194daa878b3b1969ad50415d8caf2e6356f1a7c3e6 |
| [#1584](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1584) | github_ci | UNAVAILABLE | UNAVAILABLE | GITHUB_PAGINATION_INCOMPLETE | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 3bfdcc1cd65a2c35f0d998c230871e98c341614a182fff35b84d9527677c48d1 |
| [#1585](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1585) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_TASK_INCOMPLETE | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 57dbff4bc7a71a472114b35095123ad0014fb06723e2812d94ef3be6a67e06b1 |
| [#1586](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1586) | UNAVAILABLE | OPENROUTER | UNAVAILABLE | OUTPUT_CONTINUATION_UNAVAILABLE | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | d75e1afd0da0db8ae2e1882c115642b5284b301e6f34cce0fb665cda0b52b37d |
| [#1589](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1589) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 6052fb921a70f7a03d5b6ca5218272408583f2ea892b808cd90b81c0428eb401 |
| [#1591](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1591) | github_pr | UNAVAILABLE | delivery | BUDGET_RESTRICTED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | f6a0c22b42ef3e3a1ba4e608a50cc45242402b7c4dafef04e616e0454eb5672d |
| [#1594](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1594) | github_ci | UNAVAILABLE | delivery | BUDGET_RESTRICTED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 66a4e2f9a97791f9713263ecf872d2339c1ea97d285e8c875884f402043a3579 |
| [#1596](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1596) | github_ci | UNAVAILABLE | delivery | BUDGET_RESTRICTED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 61b899911bf3271ac1254aff2f50364636d05246ba65423047de760135ca85f5 |
| [#1599](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1599) | UNAVAILABLE | UNAVAILABLE | tool_precheck | TOOL_BUDGET_EXHAUSTED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | f81b747ad3be5841d121b3a7a81ecc266a2cf2774949a97a311676bdd75033eb |
| [#1600](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1600) | github_ci | UNAVAILABLE | tool_precheck | TOOL_BUDGET_EXHAUSTED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 016f2f0def62873269120f783f7ffb49e9cb3a491453d940ef36b72159149241 |
| [#1602](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1602) | github_context | UNAVAILABLE | UNAVAILABLE | GITHUB_PAGINATION_INCOMPLETE | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 46b67290975a3d74506f266c546146559e740bc1272803c53e61532157dc7496 |
| [#1604](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1604) | github_ci | UNAVAILABLE | delivery | BUDGET_RESTRICTED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 381dd4e336e52ac0a5c8d885416216a21cb68027b9bb54f85c7da8fa365f5933 |
| [#1605](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1605) | UNAVAILABLE | UNAVAILABLE | delivery | BUDGET_RESTRICTED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 3e8ca77fd515a0f4898a3fbfeb8e334db893c71e7a42eff5a02e355e9f1d4539 |
| [#1606](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1606) | UNAVAILABLE | UNAVAILABLE | delivery | BUDGET_RESTRICTED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | fb0f7877c024b511b70dfe077b5c5358ae0b6eb78c697510b961365ae8895e1e |
| [#1608](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1608) | github_issue | UNAVAILABLE | tool_precheck | SAVED_VIEW_UNAVAILABLE | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | a1df85e12e7871d446d9bf620e79e8a6ef4d4d070541980952d3bef46adeb1b0 |
| [#1614](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1614) | github_ci | UNAVAILABLE | tool_precheck | SAVED_VIEW_UNAVAILABLE | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 09985aace8fa49f85b93d88fbcdcf0ebfd45487984e58cd59a99fed4b556ce10 |
| [#1617](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1617) | github_ci | UNAVAILABLE | tool_precheck | TOOL_BUDGET_EXHAUSTED | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 38f9ef147def7b1b016543e58b682d232cb755706d3c5f8a97e0b8e3890bda73 |
| [#1618](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1618) | github_pr | UNAVAILABLE | UNAVAILABLE | GITHUB_PAGINATION_INCOMPLETE | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 761632e237cd3f12d60e13afae1ecf74c50afcb8d65369ec43c27f4ec5cbc038 |
| [#1619](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1619) | UNAVAILABLE | OPENROUTER | delivery | NO_VISIBLE_ANSWER | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | 41d4de3ede2ca5b0128f8c84677e614c18dd69e0d5e0c13c84cbb2e88aba2918 |
| [#1621](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1621) | github_read | UNAVAILABLE | tool_precheck | TOOL_FAILURE | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | fe72130d6aa97dbe7b852056bc762182eb0b1e6a8a885f6a5f843eb2be5c7ea0 |
| [#1623](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1623) | github_search | UNAVAILABLE | tool_precheck | TOOL_FAILURE | 7d4ef7dcf62fad6eb9772df8581f73d5665b9702 | f2d4764984105acb2c3b5557dfbad632bac342cf6b79f53d2681ccb1c5332ffc |
| [#1625](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1625) | UNAVAILABLE | OPENROUTER | acceptance_verification | CHAT_FAILED | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | 2bfbc2c69e29afee8d5ae646643df156a7b9a95c0dcb53c00c4346b9ce2a4be5 |
| [#1628](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1628) | github_read | UNAVAILABLE | tool_precheck | SAVED_VIEW_UNAVAILABLE | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | bb76fcb2801eb8298da00790edaf2e9eb5f4f22aa853c3f9966687091f0c77cb |
| [#1631](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1631) | github_issue | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | 6005d4c718d4a24b6e83dc9ed38b3820dd350bf5e0e3ee6aee572fa5d91376a4 |
| [#1632](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1632) | UNAVAILABLE | OPENROUTER | acceptance_verification | CHAT_FAILED | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | 7177db37190e195e9bbefdfb6775f8f305d110401585b3eb80db8f6dc6e89e3d |
| [#1633](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1633) | UNAVAILABLE | OPENROUTER | provider_generation | TOOL_PROTOCOL_INVALID_FRAME | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | c1f4192a72f3d4d0795716a416366a2c3c659baafb549a67f9b0f2737ae8f177 |
| [#1635](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1635) | github_issue | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | 94bbd7bf6632fcf5eb99da8d7f53be4f5668592099414f2d8496f443675e5826 |
| [#1636](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1636) | UNAVAILABLE | UNAVAILABLE | tool_precheck | SAVED_VIEW_UNAVAILABLE | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | 3064239f93786d858a90cd4b95ba3a4f60f3c6c8dd70576bcdf320786b3790f1 |
| [#1641](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1641) | github_issue | UNAVAILABLE | tool_precheck | SAVED_VIEW_UNAVAILABLE | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | 63a71290d6bfc4228361fb67713f69de37439d2b0edeab164fc10768847dc883 |
| [#1646](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1646) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_TASK_INCOMPLETE | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | 3c7516dc17cbccc8c903063596fb386bfad85fb79d69271b6a3e634b143efb3d |
| [#1647](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1647) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | 2c8fb9fc8beaa2c4c8189f050100c33f0f245337 | dd505384ad806408e2ce338e4ddd2750ba610ce9c5600ed3976655cdfa9285c6 |
| [#1648](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1648) | github_search | UNAVAILABLE | tool_precheck | TOOL_FAILURE | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 30f64deb893f924e3da7cfd260882fc4865f8fd8d740ab9cc5bf4fcf855f6da6 |
| [#1649](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1649) | UNAVAILABLE | NVIDIA | model_invocation | SOURCE_UNAVAILABLE | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 3967469392a3486adb575e326b18dd4b2804ad565aa83629baa575542d295c7f |
| [#1650](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1650) | github_read | UNAVAILABLE | tool_precheck | GITHUB_EVIDENCE_REQUIRED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 91536e440c5f9e47f0c6ad31c2771b3be287c615dd49739399e86dc50e85a8aa |
| [#1651](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1651) | UNAVAILABLE | UNAVAILABLE | tool_precheck | SAVED_VIEW_UNAVAILABLE | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 0722af221629782e93a39878745d0689f6713efa4ad430900f4c448c960288df |
| [#1653](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1653) | UNAVAILABLE | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 61d0f6373b17b6494b39484f69e53bbaf769bf8bd1b93be1742ba3762194a469 |
| [#1656](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1656) | github_read | UNAVAILABLE | tool_precheck | GITHUB_VALIDATION_FAILED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 115fadf52fd204ce4d91507bd9e59b9bada600985ba2135765d4cc0e8d39abcc |
| [#1657](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1657) | github_search | UNAVAILABLE | tool_precheck | SAVED_VIEW_UNAVAILABLE | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 80a082d65101d17c8ece4beffc0fa0e15ca5cf2c782478ae4ca53be075dfde61 |
| [#1658](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1658) | UNAVAILABLE | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | c6ccba1390d970c85fd21c8ff7f1bde3ad15ed8a715e0e82086740cb557b3534 |
| [#1661](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1661) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_TASK_INCOMPLETE | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 492d8e51dbf4a2266463ef680515cc36e20a3030d58000fdfb5532a8eb88d179 |
| [#1664](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1664) | github_search | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 75ceca1060eb00ff40b7ed3733dc6ae80d4e519623e78786769d9798e9a97a9a |
| [#1668](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1668) | github_search | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 2fcae86b01f86ed71d1855f67826822b7ab4763666e21c0adb8221a77c9b47ca |
| [#1669](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1669) | UNAVAILABLE | OPENROUTER | UNAVAILABLE | GITHUB_RUN_INTERRUPTED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | c1823831930f81b2f01fb110ba5ff03b3080a9e201f3849d70ebff6231cfa26e |
| [#1671](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1671) | github_search | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 38666bde4f55cdf99080226b2cab71514c6d848005dc9471fc538152764baf9e |
| [#1674](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1674) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 6fe4a5aa4e9c6b263449e069c17f234a7b4c5416fee4e664002eaabac5d40728 |
| [#1676](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1676) | github_ci | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | f68ac2d498222e3e665bac0aee7323f771c4567fa1c3b77b8a6e40834271c3f1 |
| [#1678](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1678) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 98fc5527f296eff0c00e7ccc074dddf436403aa47d66ff984df68ad9a5f59cfc |
| [#1681](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1681) | UNAVAILABLE | OPENROUTER | provider_admission | CONTEXT_CAPACITY_EXCEEDED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 1fea3e23274d81c297d5a7af096fc5fbb7f3ee677c1b8e433ef15a1698075057 |
| [#1683](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1683) | UNAVAILABLE | OPENAI | UNAVAILABLE | CHAT_FAILED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | a2a14a6f2c8ccec233b8280eafd84e5aa4ab613273937a00f35a1c752686a1b3 |
| [#1686](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1686) | UNAVAILABLE | OPENAI | UNAVAILABLE | CHAT_FAILED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 0e77a370c92b766aa0930f454a5b04d29d0d7f8159d119c4174d8d7409b35ce6 |
| [#1688](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1688) | UNAVAILABLE | OPENAI | UNAVAILABLE | CHAT_FAILED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 372f1dc72b25df2e27b04cde184838134e32e675a9b977085db1c471efdd3b40 |
| [#1689](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1689) | UNAVAILABLE | OPENAI | acceptance_verification | GITHUB_TASK_INCOMPLETE | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | bc84b70fdef713d106010041735fbda6469b64e84e032085ba032e82aeb49737 |
| [#1690](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1690) | github_issue | UNAVAILABLE | tool_precheck | DISPATCH_POLICY_CHANGED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | d78a73930842895c019390119d6e32b34cdcc9e5137878d0a0105b7e020b34a4 |
| [#1694](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1694) | UNAVAILABLE | UNAVAILABLE | tool_precheck | RECONCILIATION_REQUIRED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 34c567e652f4d1afa522d04cdaf6432c78fe9231321fe1040fbb2c5f990d719c |
| [#1695](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1695) | UNAVAILABLE | OPENAI | tool_dispatch | RECONCILIATION_REQUIRED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 961e93cc59dc298a7d31906ef133cb2631b68915b56d301554e37c40f7fd41e6 |
| [#1702](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1702) | UNAVAILABLE | OPENAI | response_stream | CONNECTION_ABORTED | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 38513bb642e7d4add31e9f477a3de571c6596e903e02bbe1a62703c5caf27838 |
| [#1705](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1705) | UNAVAILABLE | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | a1d332feb7fa3af1d9dde6b1cce7ef389ec18a4b | 4dc07bfa6f21a519f634e1166da220184a58261edd3f95317cc6a2643df71eb3 |
| [#1706](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1706) | read_url | UNAVAILABLE | tool_precheck | TOOL_FAILURE | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 27cd78b1dc8aad5cb30ea8730bc5275decd6963d662fde42b84b14fb5f1d740e |
| [#1707](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1707) | UNAVAILABLE | OPENROUTER | claim_extraction | GITHUB_TASK_INCOMPLETE | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 7ccb4dceea1d652829a663b2e13c06a7eae7fefdb5ea1ed7358c903427e85097 |
| [#1708](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1708) | UNAVAILABLE | OPENAI | acceptance_verification | GITHUB_TASK_INCOMPLETE | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 846faace5d461dbf2dbb623d5f328388fe998601220db59f0c30c752045cd3c2 |
| [#1715](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1715) | UNAVAILABLE | OPENAI | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 14d4f0ce45672c3aa44f0599394b3574c52892c27705cdc739094a7c31b163a1 |
| [#1716](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1716) | UNAVAILABLE | OPENAI | provider_admission | CONTEXT_CAPACITY_EXCEEDED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | f0b768dcd3f70325872343ce9f09a665e122a7ffa640909870db2c6e667e5832 |
| [#1717](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1717) | UNAVAILABLE | OPENAI | provider_admission | CONTEXT_CAPACITY_EXCEEDED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 0905d25f6c79b62e018ab0d419aeb573b0667eb454adba9b935dde96f1c4e453 |
| [#1718](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1718) | UNAVAILABLE | OPENAI | acceptance_verification | GITHUB_TASK_INCOMPLETE | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | ab9ada1861285db01a2034c3f60004498a99fc64bd1cd36bb4b29faa25ed3d2a |
| [#1726](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1726) | UNAVAILABLE | OPENAI | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 7701228e3ab23e9c634d753ea17aa3c036d508bbaba8c4c5eb626ca4fca47eb4 |
| [#1729](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1729) | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | INVALID_ARGUMENTS | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 40c234482a389b3d651e9bdcfdc6203e8ece427c2ec109a923fd563928d02c96 |
| [#1730](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1730) | UNAVAILABLE | OPENAI | UNAVAILABLE | CHAT_FAILED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 6a57a861a1687150efc18104f5b15634e5bb67010eb8edf9f4a488d8817519d9 |
| [#1731](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1731) | UNAVAILABLE | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | f84677a05b80a95f9c53977ece377b86ed559f50547bfb3d22255624e0137bd2 |
| [#1732](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1732) | UNAVAILABLE | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | d0ba08821938bcc61c22901f8945b33736daa646f84bd89fa74a238c635b0c05 |
| [#1734](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1734) | UNAVAILABLE | OPENAI | provider_admission | CONTEXT_CAPACITY_EXCEEDED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 8475146a9eb81ef95cffb41b356dff4961104da4bbf4cca7ff3e97c7fd93773a |
| [#1736](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1736) | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | INVALID_ARGUMENTS | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 0fe7daf894536d3636317521ed0ccbad039dfbc9b7cb403c1b5e0e308dc20e79 |
| [#1737](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1737) | github_search | UNAVAILABLE | UNAVAILABLE | INVALID_ARGUMENTS | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 52ee37f64f4cf5feca97ebcc378b74ed134d7f577a55eabe7439e9e98b9098d7 |
| [#1738](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1738) | UNAVAILABLE | OPENAI | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 72b9e0f8ae8d06e2ff2edb51beab78c632200ec8a9d9a0e5cfedfdf5fce1a43e |
| [#1739](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1739) | UNAVAILABLE | OPENAI | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 0b3e75c70be257c8221f09aba152f5448716852c0a743f9890ddebbafdcf39d2 |
| [#1740](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1740) | UNAVAILABLE | OPENAI | provider_admission | CONTEXT_CAPACITY_EXCEEDED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | c5942f93df5f539bf0203abba0a7fa948533e5814eca302efb68e1b812890d90 |
| [#1742](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1742) | mcp | UNAVAILABLE | tool_execution | DISPATCH_OUTCOME_UNKNOWN | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 7badbde4c30fe79848033623b6eea19a2cb790682e5918ebab499f13f669053d |
| [#1743](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1743) | UNAVAILABLE | OPENROUTER | model_invocation | PROVIDER_RATE_LIMITED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 1307dbe7ff2188e8628832419c33a2966479121b55e22202e3c7fd15f793cf16 |
| [#1744](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1744) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_TASK_INCOMPLETE | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | c78ae26cb8d5a64674b07fb1a854f541dd5682de4051f68924f0b2171254deb2 |
| [#1745](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1745) | UNAVAILABLE | OPENAI | final_synthesis | COMBINED_REQUIREMENTS_UNMET | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | d5ba6f75ccb264842f77e93c1fddb19e5352a8edf0ebc78c3b0bd25a8a302e34 |
| [#1747](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1747) | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | GITHUB_PAGINATION_INCOMPLETE | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | bdef6c52d6e4da03590a83876c5a21c85007db5e14697edfbd4c5d0130ee670f |
| [#1748](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1748) | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | GITHUB_PAGINATION_INCOMPLETE | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 14eed5cb316cae2e8fa3d61f1c2d8e83a779dd80dd0bde61694f84b13afbcefb |
| [#1749](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1749) | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | GITHUB_PAGINATION_INCOMPLETE | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | 1baf888585f95f303e6e2217252492630631215be43f59eae5b3440a90524972 |
| [#1750](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1750) | UNAVAILABLE | OPENAI | provider_admission | CONTEXT_CAPACITY_EXCEEDED | ba54824b5e430818bbe4fdcad88c6dca7acf4b6b | f4b4158344235c7ef203055178878b56b9fc4323709550f9552aa0b74117a202 |
| [#1752](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1752) | UNAVAILABLE | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 15e9bd18b13c2505231ce42bd923b3b999e74fa71eb6ddf6c3503bbc5c892ad8 |
| [#1753](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1753) | UNAVAILABLE | OPENAI | UNAVAILABLE | CHAT_FAILED | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 11a2e741470b2d654baf42075f041c29a6b39f5d7a021c08857fc6a8b5a95115 |
| [#1754](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1754) | UNAVAILABLE | OPENAI | UNAVAILABLE | CHAT_FAILED | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 93d4498c6fd22dca5b7aa6b273bc6163618bdb4a9894cb3ce55cda1b5b9b3b1c |
| [#1755](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1755) | UNAVAILABLE | OPENROUTER | provider_admission | BUDGET_RESTRICTED | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 134bc5f90de99447c37af50d4e436d7d568628a13ce1de04567be0c00d2d4af0 |
| [#1756](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1756) | UNAVAILABLE | OPENAI | acceptance_verification | GITHUB_EVIDENCE_REQUIRED | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 9770e374bba200b0934a5ed39d51365e763532dc59049a99f88680f177afe6dc |
| [#1757](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1757) | UNAVAILABLE | OPENAI | provider_admission | GITHUB_TOOL_CONTEXT_BUDGET | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | acd2eb6c45a88661f81ba19592e3d95083c1e12fae620df8879a37cb620ad9ec |
| [#1758](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1758) | UNAVAILABLE | OPENROUTER | provider_admission | CONTEXT_CAPACITY_EXCEEDED | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | d87a875ad61f55b6819abfebc7c108f56cd048e5d4fc174aed11bc77863ff3a4 |
| [#1759](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1759) | UNAVAILABLE | OPENROUTER | UNAVAILABLE | CHAT_FAILED | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 136c3b3c9c7db594967c809172bcf3e0f00c9e514c771f64451830d1eed00728 |
| [#1760](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1760) | UNAVAILABLE | OPENROUTER | UNAVAILABLE | CHAT_FAILED | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 40cc4e0fde6e663e46277a5ce9e05145059e1628fb1c473d326fc8c8086f5d5a |
| [#1761](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1761) | UNAVAILABLE | LLAMA | connection_preflight | GATEWAY_CAPABILITY_UNAVAILABLE | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 9ce01a1e2d010139f83156b9a2f7718ad239fab4e8a3f3f24dfafb4ffbe61114 |
| [#1763](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1763) | UNAVAILABLE | UNAVAILABLE | tool_precheck | INVALID_ARGUMENTS | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 58de96f4c8e1af7e011de14c4ad9a249c289f27f812e344011a2ef363437e2b2 |
| [#1764](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1764) | UNAVAILABLE | OPENAI | provider_admission | GITHUB_TOOL_CONTEXT_BUDGET | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | 46c41683ef5e385e05271ad3170151c3c236ecb0ee66c837c2fb88455e7b3480 |
| [#1765](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1765) | UNAVAILABLE | OPENROUTER | acceptance_verification | GITHUB_TASK_INCOMPLETE | 0449a3a2ad95d80957e6a135a0ba9b3246567439 | a5afce361c8e9479a6566e11276da5fe08f8c629ef0d8477770e2cb9c3a8bec5 |
| [#1766](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1766) | UNAVAILABLE | OPENAI | provider_admission | GITHUB_TOOL_CONTEXT_BUDGET | 367827bb4c15370bf12b7babf321937bc80457f2 | 0e52e68dc522e45d217de7a91ebc4c0cdb6b84a062bc40fc808525b51d6124fb |
| [#1767](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1767) | UNAVAILABLE | OPENAI | UNAVAILABLE | PROVIDER_INVOCATION_FAILED | 367827bb4c15370bf12b7babf321937bc80457f2 | ff169caebc0fbb2556bbea4fca91fd3de30c8842fd2c6939d80ff8bb821451cb |

### Concurrent branch reconciliation

Before publication, the shared branch advanced to `4d8c9a6b9733e777aa9bee625a728aea71d4ce15`. The unpublished privacy change was rebased onto that exact head. Reporting compatibility, pagination/provider-limit, PR-context-race and stream-protocol capabilities from the concurrent repair are preserved alongside the new privacy capability. Combined-source reporter/readiness tests passed 151 cases, and an expanded diagnostics harness passed 9 cases including the concurrent negotiation regression. Generated inventories were rebuilt after resolving the source conflicts. No force push or competing PR is used.

### Published privacy checkpoint and subsequent CI follow-up

The privacy tree was published as `ae74ad09be13c57895339b4f3b7520a0b69fb401`, with parent `e5c8d7d8830d35ff2806ccc73704061cfc3ca58e`. It preserves the concurrent delegated-approval, Compose-clock and single-name catalog-discovery repairs. Production and test compilation, CodeQL Java/Kotlin analysis, both emulator jobs, Gateway platform jobs and Windows installer CI passed on that exact head. Those CI jobs are not physical-device, live-provider or installed-Windows acceptance.

Updating the PR description triggered the configured `edited` workflow event. [PR Validation run 44432127437](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/44432127437) was cancelled and its required aggregate correctly failed because unit/lint prerequisites were cancelled. The cancelled unit log also contains a real earlier failure: `AgentToolResolverTest.telemetryEnabledStillPublishesCompactGithubOperationsAndHonorsRevocation`, line 1532, on its fixed token threshold. Exact operation selection and telemetry contract parity had passed before that assertion. The replacement run and any subsequent source head require their own complete CI; cancellation is never treated as success.

The discovery failure was reproduced with the actual source schema factories and token estimator: context/search/read cost 1,654 tokens; adding the strengthened single-name action schema increased catalog cost from 312 to 381, making the total 2,035 instead of 1,966. Shortening only the catalog description reduces it to 339 and restores the original total limit at 1,993. The schema JSON and runtime validation remain unchanged. The regression still sums every advertised schema and keeps its original `< 2000` assertion, telemetry parity and revocation checks; failure messages now include each component cost. A focused Kotlin factory harness confirmed these measurements, unchanged schema JSON and ten valid/invalid action-discovery examples. It scaffolds surrounding types and does not substitute for the full Android resolver test.

The broad `NO_STRICT_ZDR_ENDPOINT` evidence bucket now displays **Not verified** and offers a recheck instead of claiming observed incompatibility. Missing, null or coerced retention evidence and an absent approved upstream still deny admission. Explicit provider restrictions remain **Incompatible**. Actual selection-to-status regressions pass in the expanded **43-test** privacy host harness. The production inventory was regenerated after both source changes, changed Kotlin formatting passed, and canonical fast validation again passed **540 host tests** plus its repository checks.

A concurrent process closed the audited reports and #1746 without matching installed/runtime qualification. This continuation did not make those COMPLETED closures and does not treat report-payload validation as reproduction. Existing [#1560 was reopened as the canonical live acceptance gate](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1560#issuecomment-6996267785), and a fresh open-issue read confirmed it remained open. The 104 fingerprints above remain unqualified runtime evidence, with no exact duplicate group established.

The current user instruction permits a code merge only after fresh required checks, strict main rules, applicable reviews and mergeability allow it, using the expected head SHA. Physical Android, live selected-model GitHub and OpenRouter account/route evidence, durable Android-to-installed-Windows Gateway recovery, and real Windows installer execution remain post-merge **RELEASE** gates. Neither this source checkpoint nor a later code merge authorizes publishing a release.

### New live report #1768 — Gateway capability preflight

This report arrived after the 104-report snapshot, while exact-head CI ran for `78ec4331ed1a75a7c245c6848bc528d1cb395a4d`. The same PR branch remains the only implementation branch; the base is still `main` at `326683b484f9fea4dfc585fc9732f4b3b97d9068`.

| Report | Operation | Provider | Stage | Observed failure and root-cause limit | Reported build SHA | Evidence fingerprint |
| --- | --- | --- | --- | --- | --- | --- |
| [#1768](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1768) | UNAVAILABLE; source exception identifies capability discovery | LLAMA | connection_preflight / ADMISSION | GATEWAY_CAPABILITY_UNAVAILABLE with a matched connect-timeout exception chain; endpoint/service root cause unverified | 367827bb4c15370bf12b7babf321937bc80457f2 | 4d2fec8307c5e58017635327bc0a36cd3dde728c9f059814e8e70944e837ce49 |

The app's domain-separated exception hashes exactly match `GatewayCapabilityDiscoveryException`, Ktor `ConnectTimeoutException`, and `java.net.SocketTimeoutException` in order. This is evidence of the observed timeout category, not proof of a firewall, endpoint, credential, service or schema defect. The adjacent MCP configuration warning is not promoted to a cause. The report records no model dispatch, remote acceptance NO, no request replay and no retry permission; these observations do not assert that the capability GET never reached a server. Its generic logical-continuation flag was true despite the typed capability refusal.

There is no exact fingerprint match in the prior audit. In particular, #1761 differs in build SHA and effective output limit (4,096 versus 768), so it is not a demonstrated duplicate. #1768 and canonical release gate #1560 remain open; no runtime fix or completion is claimed.

The follow-up retains closed Gateway failure-kind and optional decoder-reason fields, excluding raw paths, URLs, exception messages, response bodies and credentials. It scopes those fields to their two owning capability/isolation failure codes, requires `GATEWAY_CAPABILITY_EVIDENCE_V1` from the hosted reporter, and preserves the approved payload when an older host lacks support. Explicit no-replay/no-continuation decisions remain restrictive through the transport wrapper, without converting an earlier unknown outcome into non-dispatch. Regression and exact-head CI results must qualify this source change; installed Android/Gateway reachability, durable execution and real Windows installer acceptance remain outstanding.

Focused validation passed **29 Android-source host tests**, including actual receipt-to-projection-to-approved-payload-to-readiness submission, and **163 Python reporter/readiness tests**. The legacy readiness response remains 1,022 bytes and does not advertise the new feature; extended readiness is 1,683 bytes and advertises it. Android tests cover unknown prior outcomes, bare fallback dispatch uncertainty, explicit replay/continuation denials, malformed evidence, and full/code-only/omitted negotiation. Framework and adapter boundaries are scaffolded, including the Ktor timeout signature; these are not installed-device results. Changed Kotlin formatting and whitespace checks passed.

The shared branch then advanced to `4d204d5a815a23bf8b0ebe03c81961a5cd351c2d`, adding asynchronous scaled image previews and their instrumentation regression on top of `78ec4331ed1a75a7c245c6848bc528d1cb395a4d`. That concurrent repair is preserved by rebasing only the unpublished diagnostic continuation. The combined production inventory was regenerated at 2,701 files across 26 areas, and canonical fast validation passed **540 tests** plus its repository checks. Scoped release notes disclose the reporter and live-acceptance limitations without changing the app version or requesting publication. The prior CI run was cancelled by the new push before unit execution completed, so no full-test success is inferred from it.
