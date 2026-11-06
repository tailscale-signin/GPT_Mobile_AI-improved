# GitHub dispatch and GraphQL recovery follow-up

This review preserves the concurrent gateway/task/retention repair and its run-bound acceptance system. It adds two missing integration boundaries, not a replacement completion ledger.

## Request observations

Invocation-local request IDs now connect dispatch, HTTP-header observations, server request identifiers and terminal accounting. Explicit IDs prevent reordered, duplicated or mismatched callbacks from inventing accepted writes. HTTP acceptance is not body delivery or task completion. Interrupted reads remain separate from uncertain writes; HTTP 408 and server failures do not prove that a mutation had no effect. Cancellation closes outstanding diagnostic observations without releasing publication fences. Logged values are bounded, allowlisted metadata, not URLs, tokens or response bodies.

The ordinary REST client, native tool client, CI log reader and GraphQL client use these IDs. The existing HTTP-408 regression is retained alongside six additional observation tests.

## Ready-for-review mutation

The GraphQL mutation now uses the existing protected durable execution journal. Its claim is bound to actual query/variable inputs, not incidental workspace or presentation fields. Lost responses and GraphQL HTTP-200 error payloads retain an uncertain claim. Recovery reads the exact PR identity, open state, head and draft flag before accepting success; unresolved state never authorizes another POST. Completed receipts survive recreation of the client. Mutable response cache entries are invalidated before mutation/reconciliation observations.

Six regressions cover successful readback and restart, lost-response reconciliation, GraphQL errors, deterministic denial, changed incidental inputs and head movement during the write.

### Task acceptance after reconciliation

A further regression passed the actual lost-response and resumed tool results through the evidence book and execution ledger. It exposed a separate integration defect: the protected GraphQL journal could verify the remote PR while the task ledger still counted the original operation as unresolved.

The ledger now consumes the typed ready-for-review proof, including the repository, PR number, expected head, open state and draft flag. The proof must be live and link to the same protected execution and correlation IDs in the same task scope. Cached, unlinked or changed-target evidence cannot settle the original uncertainty. The GraphQL response's `data` field no longer hides its sibling execution receipt when tool results are unwrapped. Settling that operation still does not bypass the task's other mandatory acceptance criteria.

The reconciliation key retains the operation, PR identity and expected revisions while excluding pagination and display options. A resumed request with different presentation options can therefore settle the same protected claim; a different requested PR, head or base cannot.

## REST mutation identities and upgrade recovery

The old REST claim included a hash of every invocation argument. Actual-method regressions demonstrated duplicate issue, review and release POSTs after a lost response when only the selected ref, page or display detail changed. The HTTP claim now uses the method, path and body inside the existing account, connection, conversation and task scope. Preconditions still run, but changing a precondition or presentation option cannot authorize another identical uncertain write. A separately authorized task retains its separate scope.

Exact old-format claims remain authoritative, including claims associated with earlier credential identities and claims whose receipt body is unavailable. An exact completed result can be retained under the canonical payload identity without another request. An exact uncertain claim remains blocked. Commit readback settles both canonical and exact legacy PATCH claims only after the existing remote commit proof succeeds.

When retained context identifies unresolved old HTTP operations whose payload cannot be matched, a protected scope marker blocks unmatched writes. Rewriting or evicting the context cache cannot remove that block. It clears only after every recorded protected execution has a completed receipt. Reads and independently fenced native operations remain available while the affected HTTP publication waits for reconciliation.

The legacy format retained only the invocation hash. If the original arguments and identifying context were already lost, an arbitrary changed invocation cannot be reconstructed or proven equivalent from those hashes. This repair does not claim complete recovery of that historical evidence. Such work needs external remote-state reconciliation; a cache miss is not a verified record of a clean task history.

## Deterministic workflow errors

Full-suite validation exposed a compatibility gap between the unified GitHub 404 classification and the existing workflow retry blocker. Both `GITHUB_NOT_FOUND_OR_INACCESSIBLE` and the legacy `GITHUB_NOT_FOUND_OR_HIDDEN` now establish the same unchanged-request block. A new call ID cannot release it. A changed, authorized workflow inventory can still establish the existing recovery path without broadening permissions.

## Qualification

Formatting is checked before the source commit is prepared. Actual compilation, behavioral test results and required CI must be observed on the published candidate before merge. No device measurement, live account mutation or successful release is inferred from test source or formatting. Existing scoped-release limitations and all required gates remain unchanged.

The full Android unit suite also exposed fixtures that still expected uncompressed release-list notes, removable execution receipts, a JSON object instead of the native text envelope, and write permission for a GET request. The repaired fixtures retain exact result reconstruction, output and call ceilings, no backend replay, protected receipts across restart, exact ref request sequences and appropriate permission guidance. A presentation-capacity failure now explicitly preserves the successful upstream mutation while reporting its separate display-storage limitation. These assertions validate the new contracts without increasing their limits or skipping the failed cases.
