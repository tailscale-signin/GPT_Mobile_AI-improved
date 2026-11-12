# GitHub job recovery and native incident reporting — November 12, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Base: `main` at
`8877f0ef12ac46f87c9db6e5d79b1b661dc0f62f`. Working branch:
`fix/github-job-recovery-and-open-issues-2026-11-12`. Review:
[PR #837](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/837).

The combined branch preserves PR #834 through
`61b9034259b5052e58fa2ed5b97395615599d910` and the concurrent settled-receipt
repair `1b366647baebedbdecca311871884a676db28b75`. The repair targets observed
failure contracts and their reproducible causes. It does not turn missing
incident or physical-device evidence into successful acceptance.

## Diagnostic findings and remedies

The supplied build-141 capture has valid archive, stage and chunk integrity.
Its 20 stage hashes and 84 chunk hashes match. The retained execution evidence
contains a selection rejection, an interrupted client-tool handoff and a later
conversation conflict. App logging was disabled while network diagnostics were
enabled. None of the public report UUIDs was present in the capture, so matching
timestamps and builds alone cannot prove an individual report's cause.

| Finding | Repair or remaining evidence |
| --- | --- |
| A local saved-result read displaced the original pending assistant/tool batch. | Recover the exact pending batch across the reserved local-read suffix, images, compaction and restart. Reject reused IDs, changed arguments, another batch or a user boundary. |
| Android stopped while the gateway still owned unfinished work. | Retain a durable handoff acknowledgment with job, generation, sequence and call evidence; replay the original retained batch. Live convergence requires matching Android/gateway deployment. |
| The next turn conflicted with an existing gateway job. | Return state-specific recovery metadata and keep a rejected turn from adopting the active job as its own. |
| Continuation ignored an explicit prohibition or the saved checkpoint kind. | Keep typed recovery state and apply `resume_allowed=false` before continuation and after a new run starts. Unknown command outcomes remain reconciliation-required. |
| Required selection defaults were missing from the wire request. | Serialize defaults for the gateway execution selection and retain allowlisted validation reasons and field names. |
| A review chose an arbitrary historical comparison and expanded to thousands of files. | Admit the comparison only with a proven baseline before provider dispatch or repository probing. |
| A source read guessed the wrong path. | Permit one correction from a unique, pinned source-tree match, retaining original call evidence and rechecking authorization. |
| Pagination resumed with an offset but no search identity. | Restore only the exact retained search recipe and its cursor; a generic cursor is insufficient. |
| Repeated reads obscured already inspected coverage. | Retain immutable source-range coverage and explicit reuse evidence. This does not prove all model-generated rereads are eliminated. |
| A blob SHA was presented as a commit URL. | Keep file/blob references and commit references typed separately. |
| A lowercase conversation-conflict code became a generic failure. | Normalize the application-owned code and preserve the conversation-admission phase and primary recovery action. |
| An oversized checkpoint omitted its failure summary. | Produce a bounded summary from the same run and snapshot, including provenance and unavailable fields. Never substitute the latest unrelated run. |
| Logging availability was ambiguous. | Report app and network logging separately, with instructions for a new enabled-logging capture. Old uncaptured logs cannot be recovered. |
| Some records lacked a sequence, owned job or runtime build. | Preserve explicit partial evidence and improve future incident correlation. A rejected turn can legitimately have no owned job. |
| Reasoning preflight was slow. | The baseline gate avoids unnecessary probing, but cold/warm preflight latency remains unmeasured on the affected deployment. |
| Schema and prompt size approached the configured context budget. | Preserve the exact replay payload and stop before dispatch when it cannot fit. Overlapping counters do not prove that the captured request overflowed. |
| Native progress remained stale after gateway telemetry and privacy warnings repeated. | Publish current native-tool progress while suppressing gateway echoes; group catalog notices by actual denial reason and reported tool-entry count. Privacy decisions and individual diagnostic evidence remain intact. |
| Ordinary review duplicated the complete integration capability checklist. | Keep observed rows for ordinary reviews, retain the full catalog for integration work, and disclose retained/omitted counts. |
| Legacy policy tables did not explain the active workspace policy. | Include bounded references to the accepted current-run workspace policy. |
| Critical-section flags did not state counts or completeness. | Export explicit counts, lists and availability scope. |
| The export took about five seconds and coincided with allocation/GC activity. | Bound fallback summaries and checklist projection. No export-only allocation attribution or measured speed improvement is claimed. |
| Runtime generation and physical UI paint timing were incomplete. | Backend timing, reference-device frame traces and live lifecycle qualification remain necessary. |

Restored tool batches also seed the scheduler with settled results. Completed
calls keep their original receipts without new dispatch or completion events.
Failed restored prerequisites still block dependent writes. Decision-only
boundaries keep their original ordering and deferred actions.

## Additional Combined Mode claim repair

Review of issue #674 found a reproducible false match in the live local claim
checker. The compiled baseline classified a statement with its acquiring and
acquired companies reversed as `PARAPHRASE`. Changing active wording to a passive
sentence with the opposite participants could even produce `EXACT`, because the
fingerprint discarded voice markers. A separate disputed-declaration path could
accept a reversed statement using overlapping terms and a reporting word.

The bounded repair preserves directed participant roles before strong lexical
matching. Recognized but unresolved relations abstain; coordinated participants
need distinct counterparts. It covers active/passive and negative-passive
reversals, missing participants and ambiguous predicate matches. Independent
answer clauses can still establish valid links without reviving a rejected
reversed relation. The disputed-declaration path uses the same role guard;
supported corrections retain their separate evidence rules.

Regressions exercise direct and prepared matching, retained-passage assessment,
disputed declarations, valid active/passive comparisons and the actual delivery
session. A completed provider stream with unresolved contributor coverage must
remain incomplete rather than publish a checked final answer. These deterministic
guards do not constitute a general semantic verifier or certify factual accuracy.

## Native incident reporting

The report dialog's explicit **Send** action approves one fixed payload and
report UUID. The app retains them for background delivery and native retry.
Opening an ordinary chat or sending a chat message does not authorize a public
report. A separately selected link to an already submitted issue is optional.

New reports can include a closed, bounded context projection:

- Client occurrence time and the actual collector that captured the failure.
- Known failure stage, provider and operation families, HTTP status and retry
  delay when those facts were retained.
- Explicit dispatch, retry, resume, reconciliation and job-state observations.
- Allowlisted recovery actions and selection-validation field names.
- SHA-256 correlation values for available run, invocation, call and job IDs,
  plus a fingerprint of safe exception type/frame evidence.
- An explicit list of observations that were not retained.

Messages, prompts, raw identifiers, URLs, private paths and tool arguments are
outside this context contract. Existing redaction, private-session exclusions
and explicit public-report consent remain in force. Report IDs are retained in
a bounded local correlation index independently of app-log collection, allowing
future diagnostic captures to join an approved report to its actual evidence.
The export labels the index as app report history; it does not attribute every
entry to the conversation being exported.

The exact approved JSON must fit the existing 8 KiB service limit. A lost HTTP
response or app restart does not generate a different UUID or silently change
the payload. The service keeps its digest-bound reconciliation contract and
returns the original issue number when the same approved report is retried.
New context-bearing reports require their matching report UUID in the success
receipt. Legacy context-free reports alone can accept an absent-ID number-only
receipt; an explicit null, malformed or mismatched ID never confirms success.

### Configuration and rollout

The project-owned HTTPS reporter must be deployed separately. Server-only GitHub
App credentials remain on that service. Review, debug, preview and acceptance
APK workflows now pass the existing public repository Actions variable
`ISSUE_REPORT_ORIGIN`, matching release workflows. No destination is invented
when the variable is unset.

The readiness endpoint advertises `reportContextVersions: [1]` while preserving
its existing service identity and version. Before posting a context-bearing
report, the app checks for that support. An old service produces an explicit
update-required state with the approved report retained. It does not strip
context after approval or change the digest after an uncertain submission.
Legacy reports without context remain accepted. See
[`authentication/README.md`](../../authentication/README.md) for deployment and
the HTTP contract. Deterministic tests do not establish that a live reporter has
been deployed or that its Actions variable is configured.

## Validation and issue closure

Behavior regressions accompany the recovery, evidence, progress and reporting
changes. The canonical fast checks, gateway checks, focused reporting tests and
Android checks have separate scopes; their counts are not a combined unique
test total. Exact-current-head GitHub checks and the repository's merge rules
remain mandatory. A successful predecessor or cancelled run cannot qualify a
new head. The companion checkpoint records locally observed validation.

### Full-suite compatibility findings

The hosted unit run on `12af4f6be86c7893efe6b4aa80290405feef4010`
executed 9,414 tests: 9,404 passed, five failed and five were skipped. Its
hash-verified JUnit archive contained no errors. The separate preview workflow
reported the same five failures. Four expectations omitted the newly retained
local run/provider identity. The fifth treated two funding statements with
reversed participants as equivalent wording support. Production behavior was
correct in each case: the fix retains causal metadata and rejects the reversed
relation.

Concurrent commit `1a7f61dec1bc204b7b96d5006c5d02c1ead2a1b2` corrects
those expectations with distinct run identifiers and equivalent active/passive
funding statements, retaining the reversed pair as a negative regression.
Additional boundary cases exercise locally bound identity despite conflicting
provider fields, disjoint shared/source-specific wording ranges, and rejection
of reversed funding as semantic coverage. These are test-contract corrections;
they do not relax the report evidence or claim checks.

The exact-head API 34 and API 36 emulator jobs, four gateway matrix jobs,
CodeQL analysis and debug APK build succeeded on `12af4f6`. Those results are
predecessor evidence and do not qualify a later head. Required hosted checks
must pass on the final published repair commit before merge. The companion
checkpoint records execution scopes, the failed full-suite receipt and focused
local validation separately.

The inspected review/debug build logs show an empty `ISSUE_REPORT_ORIGIN`.
Live submission is therefore not established by the successful APK build. The
updated service must be deployed and that public Actions variable set before
rebuilding a deliverable intended to send reports. No live report was sent as
part of deterministic validation.

Reports #820–#830 contain generic historical causes that cannot be reconstructed
from the retained report bodies. Reports #831–#833 are close in time to the
supplied capture but lack the report-ID join needed to establish exact incident
identity. Reports #835 and #836 name transient GitHub transport and circuit
failures; bounded safe-read recovery does not make an external service immune
to outages. Closure must identify the repaired behavior and its evidence.

Issue #674 remains an acceptance program: 48 tasks include two implemented,
43 in progress and three not started, with no task marked done; 11 of 110 cases
have historical case-bound JVM/fixture PASS entries and 99 are not run in
the reviewed ledger. Those historical entries do not qualify the current build,
physical devices or live services. The validator now rejects
all-zero commit placeholders, and the reconciliation record reflects the active
required-check ruleset. No acceptance status is promoted by this repair.

The remaining work is not exclusively missing test receipts. The reviewed
production tree also lacks execution wiring for `CombinedCoverageValidator` and
the qualified comparison/attribution path around `CombinedProvenanceValidator`,
`VerifiedComparisonBatch` and `AttributionVerifierQualification`. Completing the
semantic coverage and attribution requirements needs that implementation as well
as its independent acceptance evidence; general CI cannot substitute for either.
The current `CombinedFinalCheck`, `CombinedEvidenceVerifier` and delivery checks
do run in production. Their local contribution accounting is separate from the
unfinished independently qualified semantic-provenance path.

No attached physical Android device was available in this environment. Voice
service behavior, process death/reboot, UI frame pacing, private model/service
availability and the remaining formal acceptance cases require corresponding
device or live-service evidence.
