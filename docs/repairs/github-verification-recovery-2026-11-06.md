# GitHub verification-report recovery — November 6, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`

Base: `main` at `223b7c9f1dbf1ecaa77b65936ca112817cfb6a9b` (merged PR #806)

Branch: `fix/github-verification-recovery-2026-11-06`

## Evidence and scope

The supplied report observes the older `e1c85e10370d0e40f80e68c6875656117fdc5ffa` revision, one `AUTH_REQUIRED` preflight rejection for issue history, one unavailable engineering graph, 43 blocked capabilities, and 16 unread continuation recipes. Its displayed groups are a sample, not the full retained receipts. This follow-up does not reconstruct those missing receipts or claim to have consumed their remote pages.

The older diagnostic reviews also identify incomplete tasks, gateway connectivity and retention failures; PR #806 already supplies the related recovery foundations. This repair starts from its merged source and does not rewrite that PR, retarget its release, change credentials, or alter any authorization/CI gate.

## Confirmed source findings and changes

### Permission and authentication reporting

`ToolApprovalManager.authorizeOrThrow` used the same `AUTH_REQUIRED` label for a denied local tool grant as the network client uses for a remote authentication failure. The selected connection's ordinary read/write policy remains authoritative. A GitHub local denial now returns `PERMISSION_DENIED`; an absent selected GitHub connection returns `PERMISSION_REVOKED`. Both remain pre-dispatch failures. An approved issue-history read continues using the existing read grant without inventing an additional approval. No credential is refreshed, removed, substituted or silently broadened.

The graph's independently authorized native-status fallback uses the same local-permission classification. Checklist readiness recognizes legacy `AUTH_REQUIRED` receipts as authorization blockers rather than reporting them authorized. Recovery guidance distinguishes legacy preflight denial from a remote authentication failure and no longer routinely tells the user to reconcile an uncertain write after a failed read. Historical failures remain recorded.

### Graph admission versus service availability

Missing or incompatible selected profiles, disabled tools/plugins, removed connections and session-only engineering memory now raise typed local graph-admission failures. `RepositoryGraphTool` reports these as precheck rejections with `dispatched=false`, configuration/grant recovery guidance, and no native fallback that could bypass the denied policy.

Transport/readiness failures retain the existing independently authorized native fallback. A successful ordinary repository read produces a degraded state, not a passing graph result: `graph_available=false` and `graph_complete=false` remain explicit. TLS, validation, cancellation and graph writes do not acquire fallback retries. The actual private gateway must still be configured and reachable; this source change cannot prove or repair its host state remotely.

### Revision-bound workflow dependencies

The old smoke planner selected a single run and only scheduled failure analysis if that chosen run failed. A successful run appearing before a different failed run at the same SHA could therefore leave failure analysis blocked despite sufficient observed identifiers.

The planner now selects an observed failed/completed run independently from the ordinary run sample. Shared selection considers eligible, successful run-list and run-read receipts; workflow jobs and aggregate status IDs are not accepted as run IDs. A newer identified attempt supersedes an older attempt. Within an attempt, newer readback replaces stale state, and a newer record without attempt metadata cannot resurrect an older terminal result. Selection remains bound to the requested commit.

Check watching uses the same current observations and validates its arguments. It no longer subscribes to an old pending observation after a newer readback shows completion. A real active chat subscription and existing authorization are still required. This is planning from observed results, not proof that the app dispatched or executed a new test.

### Pagination and resumable reporting

A hard-coded action list excluded issued remote continuations for such reads as tags, issue history/comments and log-offset pages. Exact schema-valid, visible, replayable read recipes can now be selected without that narrow action list. Branch-changing resolution, workflow mutations and graph indexing/notes are excluded from continuation replay. Failed permission/validation calls are not blindly retried.

Page-number, cursor and offset continuations share the existing bounded remote-sampling allowance. Local display fragments do not spend that allowance, and independently bounded workflow-definition traversal remains separate. No tool-call, result-retention or verification reserve was increased.

An explicit request to complete pending/remaining/unread read pagination enables exhaustive traversal under the already authorized budget. Negated requests do not expand scope. The checkpoint exposes the pagination mode, pending count, failed-page count, spent samples, an exact permitted next read when available, and why traversal must otherwise stop. Inventory summaries identify their tool/action and filters instead of displaying several indistinguishable `list inventory` rows. Missing pages remain incomplete, including after a denied or lost page.

## Disposition of all report categories

| Report finding | Disposition |
| --- | --- |
| `github_issue/history` preflight `AUTH_REQUIRED` | Correct local-versus-remote classification and actionable grant recovery; approved read path preserved. The historical receipt does not prove token expiry. |
| `repository_graph/status` unavailable | Typed admission failures, explicit degraded/absent service state and independently authorized native reads. No fabricated graph success. |
| Failure analysis blocked | Select another actually observed failed run at the exact SHA when present. A failed completed run cannot be invented when none exists. |
| Workflow dispatch blocked | Retain the owned-fixture and operation-specific authorization requirements. Creating a real workflow run is not implied by a verification report. |
| Decision tool blocked | Preserve the requirement for a genuine user decision; do not manufacture one for a score. |
| Check watch blocked | Plan only from current eligible run observations and a valid watch schema. Active chat subscription remains an execution prerequisite. |
| Partial branch/commit/run/tag/issue/PR inventories | Safe continuation admission, accurate shared sampling budget, explicit exhaustive-read scope and resumable checkpoints. Historical unread pages were not fetched in this repair. |
| 43 blocked capabilities / overall health not measured | Keep per-capability evidence and truthful blockers. Source inspection, old CI observations and local tests cannot establish all 88 capabilities or device integration health. |
| File reads versus executed tests | Keep exact-revision run/job evidence, ownership/cleanup and independent application/device acceptance requirements. |

## Validation

Local Kotlin 2.4.20 host validation executes the actual planner, scope, schema validation, read classification, receipt analysis and graph-fallback code. Sixty tests passed with a 256 MiB test heap. The harness uses DTO, storage-hash and unrelated answer-policy adapters; it is not an Android application, database, HTTP, private-gateway or device test. Dependency jars were verified against the repository's unchanged dependency-verification hashes.

Added Android regressions exercise the real approval manager for denied, removed and approved issue-history grants. These and all existing Android regressions require the full current-head CI suite. Changed Kotlin formatting is checked with the repository's pinned formatter version. An attempted local canonical host check could not finish against the partial source capture; the complete-checkout canonical check and full Android validation remain required, not waived.

Before merge, require current-head canonical `Required verification`, Android unit/heap/lint/APK and applicable emulator, diagnostics, security and contract workflows. Temporary source/toolchain/preparation workflows must not remain in the candidate tree. Physical-device behavior, private gateway readiness, authentic account grants and the original conversation's full continuation traversal remain unmeasured unless actual evidence is supplied and executed.
