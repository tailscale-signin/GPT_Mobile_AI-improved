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
