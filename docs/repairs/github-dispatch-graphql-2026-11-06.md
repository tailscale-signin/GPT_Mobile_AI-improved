# GitHub dispatch and GraphQL recovery follow-up

This review preserves the concurrent gateway/task/retention repair and its run-bound acceptance system. It adds two missing integration boundaries, not a replacement completion ledger.

## Request observations

Invocation-local request IDs now connect dispatch, HTTP-header observations, server request identifiers and terminal accounting. Explicit IDs prevent reordered, duplicated or mismatched callbacks from inventing accepted writes. HTTP acceptance is not body delivery or task completion. Interrupted reads remain separate from uncertain writes; HTTP 408 and server failures do not prove that a mutation had no effect. Cancellation closes outstanding diagnostic observations without releasing publication fences. Logged values are bounded, allowlisted metadata, not URLs, tokens or response bodies.

The ordinary REST client, native tool client, CI log reader and GraphQL client use these IDs. The existing HTTP-408 regression is retained alongside six additional observation tests.

## Ready-for-review mutation

The GraphQL mutation now uses the existing protected durable execution journal. Its claim is bound to actual query/variable inputs, not incidental workspace or presentation fields. Lost responses and GraphQL HTTP-200 error payloads retain an uncertain claim. Recovery reads the exact PR identity, open state, head and draft flag before accepting success; unresolved state never authorizes another POST. Completed receipts survive recreation of the client. Mutable response cache entries are invalidated before mutation/reconciliation observations.

Six regressions cover successful readback and restart, lost-response reconciliation, GraphQL errors, deterministic denial, changed incidental inputs and head movement during the write.

## Qualification

Formatting is checked before the source commit is prepared. Actual compilation, behavioral test results and required CI must be observed on the published candidate before merge. No device measurement, live account mutation or successful release is inferred from test source or formatting. Existing scoped-release limitations and all required gates remain unchanged.
