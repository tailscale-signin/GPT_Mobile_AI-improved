# Follow-up: full staged diagnostics a94587ab and open issue reconciliation

Date: 2026-11-15. Target: PR #1299 only, branch `fix/post-1203-diagnostics-2026-11-15`. Review parent: `d8c3daa2590f6b77ab1c1c6297c9b3c2325c09b6`.

## Source and limits

The attached first-part archive has 21 members and a valid `gptmobile.staged-diagnostics.v1` manifest: `captureCompleted=true`, `captureCompletionPercent=100` (stage completion), and `capturedFully=false`. It preserves 7,110 JSONL projections, including chunks and duplicates; this is **not** a count of independent calls. Application logs exist, but previously evicted data and external provider payloads remain unavailable. Never upload transcripts, tokens, hostnames, raw gateway URLs, or private argument values to an issue automatically.

The existing issue matrix in `partial-diagnostics-35ac528c-2026-11-15.md` continues to apply to #1276–#1320. This follow-up adds #1321–#1325. These Android reports originate from 0.9.66.7 build 141 and do **not** establish a failure of the latest PR source.

## Five newer issue dispositions

| Issue | Verified report evidence | Action and evidence required before closure |
| --- | --- | --- |
| #1321 GITHUB_TOOL_CONTEXT_BUDGET | LLAMA route-context 5,632, required schema 2,280, available schema 1,673, no dispatch | Keep hard admission; measure exact physical context, compact selected GitHub action contracts and defer unrelated tools; qualify the selected model with a larger *verified* route if available and authorized. Do not convert a configured 1,048,576 maximum into a measured allocation. |
| #1322 REASONING_ON_UNSUPPORTED | Four mixed-negative template probes; requested ON, supported control unverified, no model dispatch | Preserve a non-retryable policy rejection, show selected-model control state and explicit AUTO/qualified-model recovery options. Never silently override ON. |
| #1323 REPOSITORY_GRAPH_UNAVAILABLE | One dispatched repository-graph **index** request returned gateway HTTP 500 with index result unknown and a persisted attempt hash | Keep the original index checkpoint; read status on the exact principal/gateway/revision and reconcile committed progress. Do not replay index automatically. This follow-up projects source-bound `GRAPH_HTTP_REJECTED` alongside the existing HTTP status, attempt hash and reconciliation state. Reproduce with sanitized server-side failure classification to identify the actual internal error. |
| #1324 RECONCILIATION_REQUIRED | 3/5 required criteria verified, 73/256 calls used, 17 pending remote continuations; fixture approval and operation blockers remain | Resume only unmet criteria using stored revision/authority and terminal receipts, require explicit permission for disposable fixtures, and distinguish a missing observation from a tool failure. No speculative fixture creation or acceptance marking. |
| #1325 REASONING_OFF_UNSUPPORTED | Four mixed-negative template probes; requested OFF on same LLAMA route, supported control unverified, no dispatch | Same conservative policy as #1322: keep OFF blocked until actually supported or the user explicitly selects AUTO / a verified model. Capture probe revision, selected profile hash, outcome and dispatch=false. |

## Code adjustment and boundaries

`ToolReportDiagnostics` now admits a small closed list of graph-specific root-reason categories **only** when its persisted repository_graph action, call ID and error code agree with an execution-owned `RepositoryGraphTool.RECEIPT_SCHEMA`. It retains the source-backed HTTP response vs transport distinction without exposing raw gateway data or inventing a new call. Android and the hosted reporter share the closed allowed codes, and the regression deliberately forges wrong call IDs and arbitrary reason strings to prove they cannot masquerade as an observed root cause.

This is a **diagnostic improvement, not a repair of the historical HTTP 500 itself**. Unknown source failures require new sanitized server-side evidence. All five issues remain open until matching source-head builds, explicit fixture approval where needed, and real route/device acceptance qualify them.

## Required release gate

Run exact final-head Android compilation/unit, API34/API36 acceptance, native memory, PR validation, diagnostic audit, CodeQL, Gateway, Marketplace and Windows workflows. The unsigned-release skip is expected, not a passed test. Reconcile concurrent commits before publishing changes. The PR must not merge based on predecessor-head greens. After merge, verify main workflows and only close issues individually with precise commit and regression receipts.

## Gateway HTTP 500 error-origin follow-up

The gateway now classifies an unexpected graph exception as one of three finite, privacy-safe HTTP 500 error codes: `graph_internal_storage_error`, `graph_internal_io_error` or `graph_internal_error`. Known permission, request-validation, transport and rate-limit paths keep their original precedence. Exception strings, host paths, credentials and request bodies are **never** returned. Android recognizes these codes only on an actual HTTP 500 response, converting them into source-bound `GRAPH_INTERNAL_STORAGE`, `GRAPH_INTERNAL_IO` and `GRAPH_INTERNAL_SERVER` report reason fields. Unknown values and other HTTP statuses remain the existing `GRAPH_HTTP_REJECTED` category. New Gateway, Android and reporter-contract regression tests require exact-head CI and installed Gateway qualification; the old #1323 500 cannot be attributed retroactively to a particular failure class.
