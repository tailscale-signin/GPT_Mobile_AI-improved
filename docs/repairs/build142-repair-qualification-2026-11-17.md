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
