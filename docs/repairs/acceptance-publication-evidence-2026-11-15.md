# Acceptance publication diagnostics

Reports #1232, #1240 and #1245 retained an incomplete informational verdict with
`GITHUB_EXTRACTION_UNAVAILABLE` and `NO_MATCHING_CLAIMS`. Those observations did
not distinguish an absent publication draft from a local extraction-plan
rejection or an extraction failure. They also did not establish tool-call
exhaustion or the outcome of earlier provider requests.

The publication path now records bounded observations on the exact verdict that
is retained and delivered. Planning limits, claim selection, acceptance rules,
authorization, cancellation and mutation-replay restrictions remain unchanged.
Existing verdicts without this observation remain unavailable; the app does not
reconstruct their cause from error text, current settings or another run.

## Recorded observations

| Public field | Recorded meaning |
| --- | --- |
| `acceptance_publication_source` | The selected status, coverage, CI observation, extraction, partial fallback or newer-evidence rendering path. |
| `acceptance_extraction_observation` | Extraction was unnecessary, had no draft, was rejected by the batch/input/final-limit check, completed, or returned a failure. |
| `acceptance_draft_state` | The actual publication argument was absent, empty or present. It does not describe earlier provider output. |
| `acceptance_extraction_planned_batches` | The observed requirement-group count, including an excessive count when the unchanged batch guard rejects it. |
| `acceptance_receipt_snapshot_count` | The size of this verdict's saved receipt snapshot. It is not a count of successful operations or eligible criterion evidence. |
| `acceptance_claim_count` | The size of the recorded claims array when one exists. A missing array is not reported as zero. |
| `acceptance_extraction_required_tokens` | The estimate from the exact input comparison that rejected the plan. |
| `acceptance_extraction_input_limit_tokens` | The limit used by that same rejecting comparison. It is not a reconstructed configured or server capacity. |

Source and state values use closed enums. Receipt, claim and batch counts accept
only integers from zero through 16,000,000; token operands accept only integers
from zero through 2,147,483,647. Raw drafts, claims, receipt contents, connection
identities and exception messages are outside this contract.

If evidence advances beyond the extraction snapshot, the replacement verdict
records `EVIDENCE_ADVANCED` and its newer receipt count. Draft, extraction and
plan observations from the superseded snapshot are discarded. Existing primary
failure and uncertain-operation evidence continue to determine failure and
recovery precedence. These diagnostic fields grant no dispatch or retry
authority.

## Reporting compatibility

Any present or explicitly omitted field in this group requires
`ACCEPTANCE_PUBLICATION_EVIDENCE_V1` when using the hosted reporting route. The
app negotiates that feature at `/health/ready?capabilities=extended`, with a
4,096-byte response limit. A server returning the legacy capabilities causes the
new report to remain pending before a POST; approved fields are not stripped to
make it acceptable to an older server.

The plain `/health/ready` response preserves the legacy feature list and its
1,024-byte limit for older reports and clients. Unknown capability queries return
that legacy view. Submission receipts retain their independent 1,024-byte limit,
diagnostics remain bounded to 3,072 bytes, and approved reports remain bounded to
8,192 bytes. Native GitHub reporting retains the exact approved payload and does
not depend on the hosted readiness endpoint.

The deployment configuration checker requests the extended readiness view.
Updating the repository's reporter source does not itself deploy the service.

## Verification scope

Regressions exercise the actual session and retained verdict through the report
projection, local planning rejection operands, extraction outcomes, newer
evidence, legacy absence, primary-failure precedence and public payload bounds.
Separate compatibility checks cover hosted feature negotiation, zero POSTs when
the feature is unavailable, legacy readiness, oversized responses and unchanged
submission-receipt limits. Android execution and the complete integration gates
remain required for each published candidate; isolated host checks do not replace
them.
